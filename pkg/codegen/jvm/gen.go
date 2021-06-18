package jvm

import (
	"bytes"
	"fmt"
	"io"
	"path"
	"path/filepath"
	"reflect"
	"sort"
	"strconv"
	"strings"
	"unicode"

	"github.com/pkg/errors"
	"github.com/pulumi/pulumi/pkg/v3/codegen"
	"github.com/pulumi/pulumi/pkg/v3/codegen/schema"
	"github.com/pulumi/pulumi/sdk/v3/go/common/util/contract"
)

type typeDetails struct {
	outputType bool
	inputType  bool
	stateType  bool
	argsType   bool
	plainType  bool
}

type plainType struct {
	mod                   *modContext
	res                   *schema.Resource
	name                  string
	comment               string
	baseClass             string
	propertyTypeQualifier string
	properties            []*schema.Property
	args                  bool
	state                 bool
}

type modContext struct {
	pkg                    *schema.Package
	mod                    string
	propertyNames          map[*schema.Property]string
	types                  []*schema.ObjectType
	enums                  []*schema.EnumType
	resources              []*schema.Resource
	functions              []*schema.Function
	typeDetails            map[*schema.ObjectType]*typeDetails
	children               []*modContext
	tool                   string
	packageName            string
	packages               map[string]string
	compatibility          string
	dictionaryConstructors bool
}

// LanguageResource is derived from the schema and can be used by downstream codegen.
type LanguageResource struct {
	*schema.Resource

	Name    string // The resource name (e.g. Deployment)
	Package string // The package name (e.g. apps.v1)
}

type stringSet map[string]struct{}

func (ss stringSet) add(s string) {
	ss[s] = struct{}{}
}

func (ss stringSet) has(s string) bool {
	_, ok := ss[s]
	return ok
}

type fs map[string][]byte

func (fs fs) add(path string, contents []byte) {
	_, has := fs[path]
	contract.Assertf(!has, "duplicate file: %s", path)
	fs[path] = contents
}

func computePropertyNames(props []*schema.Property, names map[*schema.Property]string) {
	for _, p := range props {
		if info, ok := p.Language["jvm"].(JavaPropertyInfo); ok && info.Name != "" {
			names[p] = info.Name
		}
	}
}

func title(s string) string {
	if s == "" {
		return ""
	}
	runes := []rune(s)
	return string(append([]rune{unicode.ToUpper(runes[0])}, runes[1:]...))
}

func packageName(packages map[string]string, name string) string {
	if pkg, ok := packages[name]; ok {
		return pkg
	}

	return name
}

func isValueType(t schema.Type) bool {
	if _, ok := t.(*schema.EnumType); ok {
		return true
	}
	switch t {
	case schema.BoolType, schema.IntType, schema.NumberType:
		return true
	default:
		return false
	}
}

func tokenToName(tok string) string {
	components := strings.Split(tok, ":")
	contract.Assertf(len(components) == 3, "malformed token %v", tok)
	return title(components[2])
}

func (mod *modContext) tokenToPackage(tok string, qualifier string) string {
	components := strings.Split(tok, ":")
	contract.Assertf(len(components) == 3, "malformed token %v", tok)

	pkg, pkgName := "io.pulumi"+packageName(mod.packages, components[0]), mod.pkg.TokenToModule(tok)

	// TODO: check if k8s compat mode

	typ := pkg
	if pkgName != "" {
		typ += "." + packageName(mod.packages, pkgName)
	}
	if qualifier != "" {
		typ += "." + qualifier
	}

	return typ
}

func (mod *modContext) details(t *schema.ObjectType) *typeDetails {
	details, ok := mod.typeDetails[t]
	if !ok {
		details = &typeDetails{}
		mod.typeDetails[t] = details
	}

	return details
}

func (mod *modContext) propertyName(p *schema.Property) string {
	if n, ok := mod.propertyNames[p]; ok {
		return n
	}
	return title(p.Name)
}

func (mod *modContext) typeName(t *schema.ObjectType, state, input, args bool) string {
	name := tokenToName(t.Token)
	if state {
		return name + "GetArgs"
	}
	// TODO: TF and K8S compat mode

	switch {
	case input:
		return name + "Args"
	case mod.details(t).plainType:
		return name + "Result"
	}

	return name
}

var pulumiImports = []string{}

func (mod *modContext) typeString(t schema.Type, qualifier string, input, state, wrapInput, args, requireInitializers, optional bool) string {
	var typ string
	switch t := t.(type) {
	case *schema.EnumType:
		typ = mod.tokenToPackage(t.Token, "")
		typ += "."
		typ += tokenToName(t.Token)
	case *schema.ArrayType:
		var listFmt string
		switch {
		case wrapInput:
			// TODO: we have to fix types that we use internally
			listFmt, optional = "InputList<%v>", false
		case requireInitializers:
			listFmt = "List<%v>"
		default:
			listFmt, optional = "List<%v>", false
		}

		wrapInput = false
		typ = fmt.Sprintf(listFmt, mod.typeString(t.ElementType, qualifier, input, state, wrapInput, args, false, false))
	case *schema.MapType:
		var mapFmt string
		switch {
		case wrapInput:
			mapFmt, optional = "InputMap<%v>", false
		default:
			mapFmt = "Map<String, %v>"
		}

		wrapInput = false
		typ = fmt.Sprintf(mapFmt, mod.typeString(t.ElementType, qualifier, input, state, wrapInput, args, false, false))
	case *schema.ObjectType:
		namingCtx := mod
		if t.Package != mod.pkg {
			extPkg := t.Package
			var info JavaPackageInfo
			contract.AssertNoError(extPkg.ImportLanguages(map[string]schema.Language{"jvm": Importer}))
			if v, ok := t.Package.Language["jvm"].(JavaPackageInfo); ok {
				info = v
			}

			namingCtx = &modContext{
				pkg:           extPkg,
				packages:      info.Packages,
				compatibility: info.Compatibility,
			}
		}

		typ = namingCtx.tokenToPackage(t.Token, qualifier)
		if (typ == namingCtx.packageName && qualifier == "") || typ == namingCtx.packageName+"."+qualifier {
			typ = qualifier
		}

		if typ != "" {
			typ += "."
		}

		typ += mod.typeName(t, state, input, args)
	case *schema.ResourceType:
		if strings.HasPrefix(t.Token, "pulumi:providers:") {
			pkgName := strings.TrimPrefix(t.Token, "pulumi:providers:")
			typ = fmt.Sprintf("io.pulumi.%s.providers", packageName(mod.packages, pkgName))
		} else {
			namingCtx := mod
			if t.Resource != nil && t.Resource.Package != mod.pkg {
				extPkg := t.Resource.Package
				var info JavaPackageInfo
				contract.AssertNoError(extPkg.ImportLanguages(map[string]schema.Language{"java": Importer}))
				if v, ok := t.Resource.Package.Language["jvm"].(JavaPackageInfo); ok {
					info = v
				}

				namingCtx = &modContext{
					pkg:           extPkg,
					packages:      info.Packages,
					compatibility: info.Compatibility,
				}
			}
			typ = namingCtx.tokenToPackage(t.Token, "")
			if typ != "" {
				typ += "."
			}
			typ += tokenToName(t.Token)
		}
	case *schema.TokenType:
		if t.UnderlyingType != nil {
			return mod.typeString(t.UnderlyingType, qualifier, input, state, wrapInput, args, requireInitializers, optional)
		}

		typ = tokenToName(t.Token)
		if pkg := mod.tokenToPackage(t.Token, qualifier); pkg != mod.packageName {
			typ = pkg + "." + typ
		}
	case *schema.UnionType:
		elementTypeSet := stringSet{}
		var elementTypes []string
		// TODO: BOWOW
		for _, e := range t.ElementTypes {
			if typ, ok := e.(*schema.EnumType); ok && !input {
				return mod.typeString(typ.ElementType, qualifier, input, state, wrapInput, args, requireInitializers, optional)
			}

			et := mod.typeString(e, qualifier, input, state, false, args, false, false)
			if !elementTypeSet.has(et) {
				elementTypeSet.add(et)
				elementTypes = append(elementTypes, et)
			}
		}

		switch len(elementTypes) {
		case 1:
			return mod.typeString(t.ElementTypes[0], qualifier, input, state, wrapInput, args, requireInitializers, optional)
		case 2:
			unionT := "Union"
			if wrapInput {
				unionT = "InputUnion"
			}
			typ = fmt.Sprintf("%s<%s>", unionT, strings.Join(elementTypes, ", "))
			wrapInput = false
		default:
			typ = "Object"
		}
	default:
		switch t {
		case schema.BoolType:
			typ = "boolean"
		case schema.IntType:
			typ = "int"
		case schema.NumberType:
			typ = "double"
		case schema.StringType:
			typ = "String"
		case schema.ArchiveType:
			typ = "Archive"
		case schema.AssetType:
			typ = "AssetOrArchive"
		case schema.JSONType:
			if wrapInput {
				typ = "InputJson"
				wrapInput = false
			} else {
				typ = "InputJson" // TODO change this
			}
		case schema.AnyType:
			typ = "Object"
		}
	}

	if wrapInput {
		typ = fmt.Sprintf("Input<%s>", typ)
	}
	if optional {
		typ = fmt.Sprintf("Optional<%s>", typ)
	}
	return typ
}

func (mod *modContext) getConfigProperty(schemaType schema.Type) (string, string) {
	propertyType := mod.typeString(
		schemaType,
		"Types",
		false,
		false,
		false,
		false,
		false,
		false,
	)

	var getFunc string

	switch schemaType {
	case schema.StringType:
		getFunc = "get"
	case schema.BoolType:
		getFunc = "getBoolean"
	case schema.IntType:
		getFunc = "getInt"
	case schema.NumberType:
		getFunc = "getDouble"
	default:
		switch t := schemaType.(type) {
		case *schema.TokenType:
			if t.UnderlyingType != nil {
				return mod.getConfigProperty(t.UnderlyingType)
			}
		}

		getFunc = "getObject<" + propertyType + ">"
		if _, ok := schemaType.(*schema.ArrayType); ok {
			return propertyType, getFunc
		}
	}

	return fmt.Sprintf("Optional<%v>", propertyType), getFunc
}

func (mod *modContext) genHeader(w io.Writer, imports []string) {
	fmt.Fprintf(w, "// *** WARNING: this file was generated by %v. ***\n", mod.tool)
	fmt.Fprintf(w, "// *** Do not edit by hand unless you're certain you know what you are doing! ***\n")
	fmt.Fprintf(w, "\n")

	fmt.Fprintf(w, "package %s;\n", mod.packageName)

	for _, i := range imports {
		fmt.Fprintf(w, "import %s;\n", i)
	}
	if len(imports) > 0 {
		fmt.Fprintf(w, "\n")
	}
}

func jvmIdentifier(s string) string {
	// TODO
	// Some schema field names may look like $ref or $schema. Remove the leading $ to make a valid identifier.
	// This could lead to a clash if both `$foo` and `foo` are defined, but we don't try to de-duplicate now.
	if strings.HasPrefix(s, "$") {
		s = s[1:]
	}

	switch s {
	case "abstract", "as", "base", "bool",
		"break", "byte", "case", "catch",
		"char", "checked", "class", "const",
		"continue", "decimal", "default", "delegate",
		"do", "double", "else", "enum",
		"event", "explicit", "extern", "false",
		"finally", "fixed", "float", "for",
		"foreach", "goto", "if", "implicit",
		"in", "int", "interface", "internal",
		"is", "lock", "long", "namespace",
		"new", "null", "object", "operator",
		"out", "override", "params", "private",
		"protected", "public", "readonly", "ref",
		"return", "sbyte", "sealed", "short",
		"sizeof", "stackalloc", "static", "string",
		"struct", "switch", "this", "throw",
		"true", "try", "typeof", "uint",
		"ulong", "unchecked", "unsafe", "ushort",
		"using", "virtual", "void", "volatile", "while":
		return "@" + s

	default:
		return s
	}
}

func (mod *modContext) getDefaultValue(dv *schema.DefaultValue, t schema.Type) (string, error) {
	var val string
	if dv.Value != nil {
		//TODO enums
		// switch enum := t.(type) {
		// // case *schema.EnumType:
		// default:
		v, err := primitiveValue(dv.Value)
		if err != nil {
			return "", err
		}
		val = v
	}
	//TODO:::
	// if len(dv.Environment) != 0 {
	// 	getType := ""
	// 	switch t {
	// 	case schema.BoolType:
	// 		getType = "Boolean"
	// 	case schema.IntType:
	// 		getType = "Int32"
	// 	case schema.NumberType:
	// 		getType = "Double"
	// 	}

	// 	envVars := fmt.Sprintf("%q", dv.Environment[0])
	// 	for _, e := range dv.Environment[1:] {
	// 		envVars += fmt.Sprintf(", %q", e)
	// 	}

	// 	getEnv := fmt.Sprintf("Utilities.GetEnv%s(%s)", getType, envVars)
	// 	if val != "" {
	// 		val = fmt.Sprintf("%s ?? %s", getEnv, val)
	// 	} else {
	// 		val = getEnv
	// 	}
	// }

	return val, nil
}

func (pt *plainType) genInputProperty(w io.Writer, prop *schema.Property, indent string) {
	argsType := pt.args && !prop.IsPlain

	propertyName := pt.mod.propertyName(prop)
	propertyType := pt.mod.typeString(prop.Type, pt.propertyTypeQualifier, true, pt.state, argsType, argsType, false, !prop.IsRequired)

	// First generate the input attribute.

	//TODO:
	attributeArgs := ""
	if prop.IsRequired {
		attributeArgs = ", required: true"
	}
	if pt.res != nil && pt.res.IsProvider {
		json := true
		if prop.Type == schema.StringType {
			json = false
		} else if t, ok := prop.Type.(*schema.TokenType); ok && t.UnderlyingType == schema.StringType {
			json = false
		}
		if json {
			attributeArgs += ", json: true"
		}
	}

	indent = strings.Repeat(indent, 2)

	needsBackingField := false
	switch prop.Type.(type) {
	case *schema.ArrayType, *schema.MapType:
		needsBackingField = true
	}
	if prop.Secret {
		needsBackingField = true
	}

	// Next generate the input property itself. The way this is generated depends on the type of the property:
	// complex types like lists and maps need a backing field. Secret properties also require a backing field.

	//TODO:::
	if needsBackingField {
		backingFieldName := "_" + prop.Name
		requireInitializers := !pt.args || prop.IsPlain
		backingFieldType := pt.mod.typeString(prop.Type, pt.propertyTypeQualifier, true, pt.state, argsType, argsType, requireInitializers, false)

		fmt.Fprintf(w, "%sprivate %s? %s;\n", indent, backingFieldType, backingFieldName)

		if prop.Comment != "" {
			fmt.Fprintf(w, "\n")
			printComment(w, prop.Comment, indent)
		}
		// TODO :??printObsoleteAttribute(w, prop.DeprecationMessage, indent)

		switch prop.Type.(type) {
		case *schema.ArrayType, *schema.MapType:
			// Note that we use the backing field type--which is just the property type without any nullable annotation--to
			// ensure that the user does not see warnings when initializing these properties using object or collection
			// initializers.
			fmt.Fprintf(w, "%spublic %s %s\n", indent, backingFieldType, propertyName)
			//TODO: seter i getter?
			fmt.Fprintf(w, "%s{\n", indent)
			fmt.Fprintf(w, "%s    get => %[2]s ?? (%[2]s = new %[3]s());\n", indent, backingFieldName, backingFieldType)
		default:
			fmt.Fprintf(w, "%spublic %s? %s\n", indent, backingFieldType, propertyName)
			fmt.Fprintf(w, "%s{\n", indent)
			fmt.Fprintf(w, "%s    get => %s;\n", indent, backingFieldName)
		}
		if prop.Secret {
			fmt.Fprintf(w, "%s    set\n", indent)
			fmt.Fprintf(w, "%s    {\n", indent)
			// Since we can't directly assign the Output from CreateSecret to the property, use an Output.All or
			// Output.Tuple to enable the secret flag on the data. (If any input to the All/Tuple is secret, then the
			// Output will also be secret.)
			switch t := prop.Type.(type) {
			case *schema.ArrayType:
				fmt.Fprintf(w, "%s        var emptySecret = Output.CreateSecret(ImmutableArray.Create<%s>());\n", indent, t.ElementType.String())
				fmt.Fprintf(w, "%s        %s = Output.All(value, emptySecret).Apply(v => v[0]);\n", indent, backingFieldName)
			case *schema.MapType:
				fmt.Fprintf(w, "%s        var emptySecret = Output.CreateSecret(ImmutableDictionary.Create<string, %s>());\n", indent, t.ElementType.String())
				fmt.Fprintf(w, "%s        %s = Output.All(value, emptySecret).Apply(v => v[0]);\n", indent, backingFieldName)
			default:
				fmt.Fprintf(w, "%s        var emptySecret = Output.CreateSecret(0);\n", indent)
				fmt.Fprintf(w, "%s        %s = Output.Tuple<%s?, int>(value, emptySecret).Apply(t => t.Item1);\n", indent, backingFieldName, backingFieldType)
			}
			fmt.Fprintf(w, "%s    }\n", indent)
		} else {
			fmt.Fprintf(w, "%s    set => %s = value;\n", indent, backingFieldName)
		}
		fmt.Fprintf(w, "%s}\n", indent)
	} else {
		initializer := ""
		if prop.IsRequired && (!isValueType(prop.Type) || (pt.args && !prop.IsPlain)) {
			initializer = " = null!;"
		}

		printComment(w, prop.Comment, indent)
		fmt.Fprintf(w, "%spublic %s %s { get; set; }%s\n", indent, propertyType, propertyName, initializer)
	}
}

func (mod *modContext) genConfig(variables []*schema.Property) (string, error) {
	w := &bytes.Buffer{}

	mod.genHeader(w, []string{"Collections"})

	fmt.Fprintf(w, "public class Config {\n")
	fmt.Fprintf(w, "\tprivate final static pulumi.Config config = new pulumi.Config(\"%v\");\n", mod.pkg.Name)

	// Generate Java getters and setters
	for _, p := range variables {
		propertyType, getFunc := mod.getConfigProperty(p.Type)
		propertyName := mod.propertyName(p)

		initializer := fmt.Sprintf("__config.%s(\"%s\")", getFunc, p.Name)

		// TODO get default value

		// TODO print comment

		fmt.Fprintf(w, "\tpublic static %s get%s () { return %s; }\n", propertyType, propertyName, initializer)

		// TODO setter
	}

	// TODO generate nested types

	fmt.Fprintf(w, "\t}\n")
	fmt.Fprintf(w, "}")

	return w.String(), nil
}

func (mod *modContext) gen(fs fs) error {
	pkgComponents := strings.Split(mod.packageName, ".")
	if len(pkgComponents) > 0 {
		pkgComponents = pkgComponents[2:]
	}

	dir := path.Join(pkgComponents...)

	var files []string
	for p := range fs {
		d := path.Dir(p)
		if d == "." {
			d = ""
		}

		if d == dir {
			files = append(files, p)
		}
	}

	addFile := func(name, contents string) {
		p := path.Join(dir, name)
		files = append(files, p)
		fs.add(p, []byte(contents))
	}

	// Ensure that the target module directory contains a README.md file.
	readme := mod.pkg.Description
	if readme != "" && readme[len(readme)-1] != '\n' {
		readme += "\n"
	}
	fs.add(filepath.Join(dir, "README.md"), []byte(readme))

	if mod.mod == "config" {
		if len(mod.pkg.Config) > 0 {
			config, err := mod.genConfig(mod.pkg.Config)
			if err != nil {
				return err
			}

			addFile("Config.java", config)
			return nil
		}
	}

	// Resources
	for _, r := range mod.resources {
		imports := map[string]codegen.StringSet{}
		mod.getImports(r, imports)

		buffer := &bytes.Buffer{}
		var additionalImports []string
		for _, i := range imports {
			additionalImports = append(additionalImports, i.SortedValues()...)
		}
		sort.Strings(additionalImports)
		importStrings := pulumiImports
		importStrings = append(importStrings, additionalImports...)
		mod.genHeader(buffer, importStrings)

		if err := mod.genResource(buffer, r); err != nil {
			return err
		}

		addFile(resourceName(r)+".java", buffer.String())
	}

	// Functions
	// TODO

	// Nested types
	for _, t := range mod.types {
		if mod.details(t).inputType {
			buffer := &bytes.Buffer{}
			mod.genHeader(buffer, pulumiImports)

			if mod.details(t).argsType {
				if err := mod.genType(buffer, t, "Inputs", true, false, true, 1); err != nil {
					return err
				}
			}
			if mod.details(t).plainType {
				if err := mod.genType(buffer, t, "Inputs", true, false, false, 1); err != nil {
					return err
				}
			}
			addFile(path.Join("Inputs", tokenToName(t.Token)+"Args.java"), buffer.String())
		}
		if mod.details(t).stateType {
			buffer := &bytes.Buffer{}
			mod.genHeader(buffer, pulumiImports)

			if err := mod.genType(buffer, t, "Inputs", true, true, true, 1); err != nil {
				return err
			}
			addFile(path.Join("Inputs", tokenToName(t.Token)+"GetArgs.java"), buffer.String())
		}
		if mod.details(t).outputType {
			buffer := &bytes.Buffer{}
			mod.genHeader(buffer, pulumiImports)

			if err := mod.genType(buffer, t, "Outputs", false, false, false, 1); err != nil {
				return err
			}
			addFile(path.Join("Outputs", tokenToName(t.Token)+".java"), buffer.String())
		}
	}

	// Enums
	// TODO

	return nil
}

func (mod *modContext) genType(w io.Writer, obj *schema.ObjectType, propertyTypeQualifier string, input, state, args bool, level int) error {
	pt := &plainType{
		mod:                   mod,
		name:                  mod.typeName(obj, state, input, args),
		comment:               obj.Comment,
		propertyTypeQualifier: propertyTypeQualifier,
		properties:            obj.Properties,
		state:                 state,
		args:                  args,
	}

	if input {
		pt.baseClass = "ResourceArgs"
		if !args && mod.details(obj).plainType {
			pt.baseClass = "InvokeArgs"
		}
		return pt.genInputType(w, level)
	}

	pt.genOutputType(w, level)
	return nil
}

func (pt *plainType) genInputType(w io.Writer, level int) error {
	indent := strings.Repeat("    ", level)

	fmt.Fprintf(w, "\n")

	// Open the class.
	printComment(w, pt.comment, indent)
	fmt.Fprintf(w, "%spublic final class %s extends io.pulumi.%s {\n", indent, pt.name, pt.baseClass)

	// Declare each input property.
	for _, p := range pt.properties {
		pt.genInputProperty(w, p, indent)
		fmt.Fprintf(w, "\n")
	}

	// Generate a constructor that will set default values.
	fmt.Fprintf(w, "%s    public %s() {\n", indent, pt.name)
	for _, prop := range pt.properties {
		if prop.DefaultValue != nil {
			dv, err := pt.mod.getDefaultValue(prop.DefaultValue, prop.Type)
			if err != nil {
				return err
			}
			propertyName := pt.mod.propertyName(prop)
			fmt.Fprintf(w, "%s        %s = %s;\n", indent, propertyName, dv)
		}
	}
	fmt.Fprintf(w, "%s    }\n", indent)

	// Close the class.
	fmt.Fprintf(w, "%s}\n", indent)

	return nil
}

func (pt *plainType) genOutputType(w io.Writer, level int) {
	indent := strings.Repeat("    ", level)

	fmt.Fprintf(w, "\n")

	// Open the class and attribute it appropriately.
	fmt.Fprintf(w, "%spublic final class %s {\n", indent, pt.name)

	// Generate each output field.
	for _, prop := range pt.properties {
		fieldName := pt.mod.propertyName(prop)
		required := prop.IsRequired
		fieldType := pt.mod.typeString(prop.Type, pt.propertyTypeQualifier, false, false, false, false, false, !required)
		printComment(w, prop.Comment, indent+"    ")
		fmt.Fprintf(w, "%s    public final %s %s;\n", indent, fieldType, fieldName)
	}
	if len(pt.properties) > 0 {
		fmt.Fprintf(w, "\n")
	}

	// Generate an appropriately-attributed constructor that will set this types' fields.
	fmt.Fprintf(w, "%s    private %s(", indent, pt.name)

	// Generate the constructor parameters.
	for i, prop := range pt.properties {
		paramName := jvmIdentifier(prop.Name)
		required := prop.IsRequired
		paramType := pt.mod.typeString(prop.Type, pt.propertyTypeQualifier, false, false, false, false, false, !required)

		terminator := ""
		if i != len(pt.properties)-1 {
			terminator = ",\n"
		}

		paramDef := fmt.Sprintf("%s %s%s", paramType, paramName, terminator)
		if len(pt.properties) > 1 {
			paramDef = fmt.Sprintf("\n%s        %s", indent, paramDef)
		}
		fmt.Fprint(w, paramDef)
	}

	// Generate the constructor body.
	fmt.Fprintf(w, "\n%s    ) {\n", indent)
	for _, prop := range pt.properties {
		paramName := jvmIdentifier(prop.Name)
		fieldName := pt.mod.propertyName(prop)
		if fieldName == paramName {
			// Avoid a no-op in case of field and property name collision.
			fieldName = "this." + fieldName
		}
		fmt.Fprintf(w, "%s        %s = %s;\n", indent, fieldName, paramName)
	}
	fmt.Fprintf(w, "%s    }\n", indent)

	// Close the class.
	fmt.Fprintf(w, "%s}\n", indent)
}

func (mod *modContext) genResource(w io.Writer, r *schema.Resource) error {
	name := resourceName(r)

	// Write the documentation comment for the resource class
	printComment(w, codegen.FilterExamples(r.Comment, "jvm"), "    ")

	// Open the class
	className := name
	var baseType string
	optionsType := "CustomResourceOptions"
	switch {
	case r.IsProvider:
		baseType = "Pulumi.ProviderResource"
	case r.IsComponent:
		baseType = "Pulumi.ComponentResource"
		optionsType = "ComponentResourceOptions"
	default:
		baseType = "Pulumi.CustomResource"
	}

	// Deprecation

	//	fmt.Fprintf(w, "    [%sResourceType(\"%s\")]\n", namespaceName(mod.namespaces, mod.pkg.Name), r.Token)
	fmt.Fprintf(w, "    public class %s extends %s {\n", className, baseType)

	var secretProps []string
	// Emit all output properties
	for _, prop := range r.Properties {
		// Write the property attribute
		propertyName := mod.propertyName(prop)
		required := prop.IsRequired
		propertyType := mod.typeString(prop.Type, "Outputs", false, false, false, false, false, !required)

		// Workaround the fact taht provider inputs come back as strings.
		if r.IsProvider && !schema.IsPrimitiveType(prop.Type) {
			propertyType = "String"
			if !prop.IsRequired {
				propertyType = fmt.Sprintf("Optional<%s>", propertyType)
			}
		}

		if prop.Secret {
			secretProps = append(secretProps, prop.Name)
		}

		printComment(w, prop.Comment, "    "+"    ")
		fmt.Fprintf(w, "        public Output<%s> %s;", propertyType, propertyName)
		fmt.Fprintf(w, "\n")
	}
	if len(r.Properties) > 0 {
		fmt.Fprintf(w, "\n")
	}

	// Emit the class constructor
	argsClassName := className + "Args"
	argsType := argsClassName

	allOptionalInputs := true
	hasConstInputs := false
	for _, prop := range r.InputProperties {
		allOptionalInputs = allOptionalInputs && !prop.IsRequired
		hasConstInputs = hasConstInputs || prop.ConstValue != nil
	}
	if allOptionalInputs {
		// If the number of required inputs properties was zero, we can make the args object optional.
		// TODO : no default arguments in java
		argsType = fmt.Sprintf("Optional<%s>", argsType)
	}

	tok := r.Token
	if r.IsProvider {
		tok = mod.pkg.Name
	}

	argsOverride := fmt.Sprintf("args.isPresent()?args.get(): new %sArgs()", className)
	if hasConstInputs {
		argsOverride = "makeArgs(args)"
	}
	// TODO JAVADOCS

	fmt.Fprintf(w, "        public %s(string name, %s args, Optional<%s> options) {\n", className, argsType, optionsType)
	if r.IsComponent {
		fmt.Fprintf(w, "            super(\"%s\", name, %s, makeResourceOptions(options, \"\"), true)\n", tok, argsOverride)
	} else {
		fmt.Fprintf(w, "            super(\"%s\", name, %s, makeResourceOptions(options, \"\"))\n", tok, argsOverride)
	}
	fmt.Fprintf(w, "        }\n")
	// TODO dictionary constructor

	// TODO: private constuctor for te use of 'get'``

	// Write the method that will calculate the resource arguments.
	if hasConstInputs {
		fmt.Fprintf(w, "\n")
		fmt.Fprintf(w, "    private static %s makeArgs(%s args) {", argsType, argsType)
		fmt.Fprintf(w, "        %s args = new %s();\n", argsClassName, argsClassName)
		for _, prop := range r.InputProperties {
			if prop.ConstValue != nil {
				v, err := primitiveValue(prop.ConstValue)
				if err != nil {
					return err
				}
				fmt.Fprintf(w, "        args.%s = %s;\n", mod.propertyName(prop), v)
			}
		}
		fmt.Fprintf(w, "        return args;\n")
		fmt.Fprintf(w, "    }\n")
	}

	// Write the method that will calculate the resource options.
	fmt.Fprintf(w, "\n")
	fmt.Fprintf(w, "        private static %s makeResourceOptions(%s optins, Optional<Input<String>> id) {\n", optionsType, optionsType)
	fmt.Fprintf(w, "            %s defaultOptions = new %s();\n", optionsType, optionsType)
	fmt.Fprintf(w, "            defaultOptions.Verion = Unilities.Version;\n")
	if len(r.Aliases) > 0 {
		// TODO aliases
	}
	// TODO secretProps
	fmt.Fprintf(w, "            %s merged = %s.merge(defaultOptions, options);\n", optionsType, optionsType)
	fmt.Fprintf(w, "            return merged;\n")
	fmt.Fprintf(w, "        }\n")

	// TODO get method

	// Close the class
	fmt.Fprintf(w, "    }\n")
	return nil
}

func primitiveValue(value interface{}) (string, error) {
	v := reflect.ValueOf(value)
	if v.Kind() == reflect.Interface {
		v = v.Elem()
	}

	switch v.Kind() {
	case reflect.Bool:
		if v.Bool() {
			return "true", nil
		}
		return "false", nil
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32:
		return strconv.FormatInt(v.Int(), 10), nil
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32:
		return strconv.FormatUint(v.Uint(), 10), nil
	case reflect.Float32, reflect.Float64:
		return strconv.FormatFloat(v.Float(), 'f', -1, 64), nil
	case reflect.String:
		return fmt.Sprintf("%q", v.String()), nil
	default:
		return "", errors.Errorf("unsupported default value of type %T", value)
	}
}

func printComment(w io.Writer, comment string, indent string) {
	// TODO in java style
	lines := strings.Split(comment, "\n")
	for len(lines) > 0 && lines[len(lines)-1] == "" {
		lines = lines[:len(lines)-1]
	}

	if len(lines) > 0 {
		for _, l := range lines {
			fmt.Fprintf(w, "%s// %s\n", indent, l)
		}
	}
}

func resourceName(r *schema.Resource) string {
	if r.IsProvider {
		return "Provider"
	}
	return tokenToName(r.Token)
}

func (mod *modContext) getImports(member interface{}, imports map[string]codegen.StringSet) {
	seen := codegen.Set{}
	switch member := member.(type) {
	case *schema.ObjectType:
		for _, p := range member.Properties {
			mod.getTypeImports(p.Type, true, imports, seen)
		}
		return
	case *schema.ResourceType:
		mod.getTypeImports(member, true, imports, seen)
		return
	case *schema.Resource:
		for _, p := range member.Properties {
			mod.getTypeImports(p.Type, false, imports, seen)
		}
		for _, p := range member.InputProperties {
			mod.getTypeImports(p.Type, false, imports, seen)
		}
		return
	case *schema.Function:
		if member.Inputs != nil {
			mod.getTypeImports(member.Inputs, false, imports, seen)
		}
		if member.Outputs != nil {
			mod.getTypeImports(member.Outputs, false, imports, seen)
		}
		return
	case []*schema.Property:
		for _, p := range member {
			mod.getTypeImports(p.Type, false, imports, seen)
		}
		return
	default:
		return
	}
}

func (mod *modContext) getTypeImports(t schema.Type, recurse bool, imports map[string]codegen.StringSet, seen codegen.Set) {
	if seen.Has(t) {
		return
	}
	seen.Add(t)

	switch t := t.(type) {
	case *schema.ArrayType:
		mod.getTypeImports(t.ElementType, recurse, imports, seen)
		return
	case *schema.MapType:
		mod.getTypeImports(t.ElementType, recurse, imports, seen)
		return
	case *schema.ObjectType:
		for _, p := range t.Properties {
			mod.getTypeImports(p.Type, recurse, imports, seen)
		}
		return
	case *schema.ResourceType:
		// If it's an external resource, we'll be using fully-qualified type names, so there's no need for an import.
		if t.Resource != nil && t.Resource.Package != mod.pkg {
			return
		}
		modName, name, modPath := mod.pkg.TokenToModule(t.Token), tokenToName(t.Token), ""
		if modName != mod.mod {
			mp, err := filepath.Rel(mod.mod, modName)
			contract.Assert(err == nil)
			if path.Base(mp) == "." {
				mp = path.Dir(mp)
			}
			modPath = filepath.ToSlash(mp)
		}
		if len(modPath) == 0 {
			return
		}
		if imports[modPath] == nil {
			imports[modPath] = codegen.NewStringSet()
		}
		imports[modPath].Add(name)
	case *schema.TokenType:
		return
	case *schema.UnionType:
		for _, e := range t.ElementTypes {
			mod.getTypeImports(e, recurse, imports, seen)
		}
		return
	default:
		return
	}
}

func visitObjectTypes(properties []*schema.Property, visitor func(*schema.ObjectType, bool)) {
	codegen.VisitTypeClosure(properties, func(t codegen.Type) {
		if o, ok := t.Type.(*schema.ObjectType); ok {
			visitor(o, t.Plain)
		}
	})
}

func generateModuleContextMap(tool string, pkg *schema.Package) (map[string]*modContext, *JavaPackageInfo, error) {
	infos := map[*schema.Package]*JavaPackageInfo{}
	var getPackageInfo = func(p *schema.Package) *JavaPackageInfo {
		info, ok := infos[p]
		if !ok {
			if err := p.ImportLanguages(map[string]schema.Language{"jvm": Importer}); err != nil {
				panic(err)
			}
			jvmInfo, _ := pkg.Language["jvm"].(JavaPackageInfo)
			info = &jvmInfo
			infos[p] = info
		}
		return info
	}
	infos[pkg] = getPackageInfo(pkg)

	propertyNames := map[*schema.Property]string{}
	computePropertyNames(pkg.Config, propertyNames)
	computePropertyNames(pkg.Provider.InputProperties, propertyNames)
	for _, r := range pkg.Resources {
		computePropertyNames(r.Properties, propertyNames)
		computePropertyNames(r.InputProperties, propertyNames)
		if r.StateInputs != nil {
			computePropertyNames(r.StateInputs.Properties, propertyNames)
		}
	}
	for _, f := range pkg.Functions {
		if f.Inputs != nil {
			computePropertyNames(f.Inputs.Properties, propertyNames)
		}
		if f.Outputs != nil {
			computePropertyNames(f.Outputs.Properties, propertyNames)
		}
	}
	for _, t := range pkg.Types {
		if obj, ok := t.(*schema.ObjectType); ok {
			computePropertyNames(obj.Properties, propertyNames)
		}
	}

	// group resources, types, and functions into Go packages
	modules := map[string]*modContext{}
	details := map[*schema.ObjectType]*typeDetails{}

	var getMod func(modName string, p *schema.Package) *modContext
	getMod = func(modName string, p *schema.Package) *modContext {
		mod, ok := modules[modName]
		if !ok {
			info := getPackageInfo(p)
			pkgName := "io.pulumi." + packageName(info.Packages, pkg.Name)
			if modName != "" {
				pkgName += "." + packageName(info.Packages, modName)
			}
			mod = &modContext{
				pkg:                    p,
				mod:                    modName,
				tool:                   tool,
				packageName:            pkgName,
				packages:               info.Packages,
				typeDetails:            details,
				propertyNames:          propertyNames,
				compatibility:          info.Compatibility,
				dictionaryConstructors: info.DictionaryConstructors,
			}

			if modName != "" {
				parentName := path.Dir(modName)
				if parentName == "." {
					parentName = ""
				}
				parent := getMod(parentName, p)
				parent.children = append(parent.children, mod)
			}

			if p == pkg {
				modules[modName] = mod
			}
		}
		return mod
	}

	getModFromToken := func(token string, p *schema.Package) *modContext {
		return getMod(p.TokenToModule(token), p)
	}

	// Create the config module if necessary.
	if len(pkg.Config) > 0 {
		cfg := getMod("config", pkg)
		cfg.packageName = "io.pulumi." + packageName(infos[pkg].Packages, pkg.Name)
	}

	visitObjectTypes(pkg.Config, func(t *schema.ObjectType, _ bool) {
		getModFromToken(t.Token, pkg).details(t).outputType = true
	})

	// Find input and output types referenced by resources.
	scanResource := func(r *schema.Resource) {
		mod := getModFromToken(r.Token, pkg)
		mod.resources = append(mod.resources, r)
		visitObjectTypes(r.Properties, func(t *schema.ObjectType, _ bool) {
			getModFromToken(t.Token, t.Package).details(t).outputType = true
		})
		visitObjectTypes(r.InputProperties, func(t *schema.ObjectType, plain bool) {
			if r.IsProvider {
				getModFromToken(t.Token, t.Package).details(t).outputType = true
			}
			getModFromToken(t.Token, t.Package).details(t).inputType = true
			if plain {
				getModFromToken(t.Token, t.Package).details(t).plainType = true
			} else {
				getModFromToken(t.Token, t.Package).details(t).argsType = true
			}
		})
		if r.StateInputs != nil {
			visitObjectTypes(r.StateInputs.Properties, func(t *schema.ObjectType, _ bool) {
				getModFromToken(t.Token, t.Package).details(t).inputType = true
				getModFromToken(t.Token, t.Package).details(t).stateType = true
			})
		}
	}

	scanResource(pkg.Provider)
	for _, r := range pkg.Resources {
		scanResource(r)
	}

	// Find input and output types referenced by functions.
	for _, f := range pkg.Functions {
		mod := getModFromToken(f.Token, pkg)
		mod.functions = append(mod.functions, f)
		if f.Inputs != nil {
			visitObjectTypes(f.Inputs.Properties, func(t *schema.ObjectType, _ bool) {
				details := getModFromToken(t.Token, t.Package).details(t)
				details.inputType = true
				details.plainType = true
			})
		}
		if f.Outputs != nil {
			visitObjectTypes(f.Outputs.Properties, func(t *schema.ObjectType, _ bool) {
				details := getModFromToken(t.Token, t.Package).details(t)
				details.outputType = true
				details.plainType = true
			})
		}
	}

	// Find nested types.
	for _, t := range pkg.Types {
		switch typ := t.(type) {
		case *schema.ObjectType:
			mod := getModFromToken(typ.Token, pkg)
			mod.types = append(mod.types, typ)
		case *schema.EnumType:
			mod := getModFromToken(typ.Token, pkg)
			mod.enums = append(mod.enums, typ)
		default:
			continue
		}
	}

	return modules, infos[pkg], nil
}

// LanguageResources returns a map of resources that can be used by downstream codegen. The map
// key is the resource schema token.
func LanguageResources(tool string, pkg *schema.Package) (map[string]LanguageResource, error) {
	modules, info, err := generateModuleContextMap(tool, pkg)
	if err != nil {
		return nil, err
	}

	resources := map[string]LanguageResource{}
	for modName, mod := range modules {
		if modName == "" {
			continue
		}
		for _, r := range mod.resources {
			lr := LanguageResource{
				Resource: r,
				Package:  packageName(info.Packages, modName),
				Name:     tokenToName(r.Token),
			}
			resources[r.Token] = lr
		}
	}

	return resources, nil
}

func GeneratePackage(tool string, pkg *schema.Package, extraFiles map[string][]byte) (map[string][]byte, error) {
	modules, _, err := generateModuleContextMap(tool, pkg)
	if err != nil {
		return nil, err
	}

	//className := "io.pulumi." + packageName(info.Packages, pkg.Name)

	// Generate each module.
	files := fs{}
	for p, f := range extraFiles {
		files.add(p, f)
	}

	for _, mod := range modules {
		if err := mod.gen(files); err != nil {
			return nil, err
		}
	}

	// TODO generate gradle project

	return files, nil
}
