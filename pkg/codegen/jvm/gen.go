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
	name       string
	comment    string
	baseClass  string
	properties []*schema.Property
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
	return p.Name
}

func (mod *modContext) typeName(t *schema.ObjectType) string {
	name := tokenToName(t.Token)
	return name + "Args"
}

// Get the name for the token
func tokenToName(tok string) string {
	components := strings.Split(tok, ":")
	contract.Assertf(len(components) == 3, "malformed token %v", tok)
	return title(components[2])
}

// Get the module in which token should reside with additional qualier? TODO??
func (mod *modContext) tokenToPackage(tok string, qualifier string) string {
	components := strings.Split(tok, ":")
	contract.Assertf(len(components) == 3, "malformed token %v", tok)

	pkg := fmt.Sprintf("%s.%s", basePackage, packageName(mod.packages, components[0]))
	pkgName := mod.pkg.TokenToModule(tok)

	typ := pkg
	if pkgName != "" {
		typ += "." + packageName(mod.packages, pkgName)
	}
	if qualifier != "" {
		typ += "." + qualifier
	}

	return typ
}

// check if a resource is a provider
func isProvider(t *schema.ResourceType) bool {
	return strings.HasPrefix(t.Token, "pulumi:providers:")

}

var pulumiImports = []string{
	// "java.util.Optional",
	// "java.util.List",
}

const basePackage = "io.pulumi"

const providerResource = basePackage + ".ProviderResource"
const componentResource = basePackage + ".ComponentResource"
const customResource = basePackage + ".CustomResource"

const resourceArgs = basePackage + ".ResourceArgs"

const customResourceOptions = basePackage + ".CustomResourceOptions"
const componentResourceOptions = basePackage + ".ComponentResourceOptions"

const inputType = basePackage + ".Input"
const outputType = basePackage + ".Output"
const aliasType = basePackage + ".Alias"

const listType = "java.util.List" // TODO: change to interface vs implementations
const mapType = "java.util.Map"
const optionalType = "java.util.Optional"

const listImplementationFactory = "com.google.common.collect.ImmutableList.of"
const optionalFactory = "java.util.Optional.of"

const nullableAnnot = "javax.annotation.Nullable"

func (mod *modContext) typeString(t schema.Type) string {
	var typ string
	switch t := t.(type) {
	case *schema.EnumType:
		typ = fmt.Sprintf("%s.%s", mod.tokenToPackage(t.Token, ""), tokenToName(t.Token))
	case *schema.ArrayType:
		typ = fmt.Sprintf("%s<%s>", listType, mod.typeString(t.ElementType))
	case *schema.MapType:
		typ = fmt.Sprintf("%s<String, %s>", mapType, mod.typeString(t.ElementType))
	case *schema.ObjectType:
		//TODO: #####
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
		qualifier := ""
		typ = namingCtx.tokenToPackage(t.Token, qualifier)
		if (typ == namingCtx.packageName && qualifier == "") || typ == namingCtx.packageName+"."+qualifier {
			typ = qualifier
		}

		if typ != "" {
			typ += "."
		}

		typ += mod.typeName(t)
		// ######
	case *schema.ResourceType:
		if isProvider(t) {
			typ = fmt.Sprintf("%s.%s", mod.tokenToPackage(t.Token, ""), tokenToName(t.Token))
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
			return mod.typeString(t.UnderlyingType)
		}

		typ = tokenToName(t.Token)
		if pkg := mod.tokenToPackage(t.Token, ""); pkg != mod.packageName {
			typ = pkg + "." + typ
		}
	case *schema.UnionType:
		// TODO: Dont know how to handle union types in java
		input := false
		elementTypeSet := stringSet{}
		var elementTypes []string
		for _, e := range t.ElementTypes {
			if typ, ok := e.(*schema.EnumType); ok && !input {
				return mod.typeString(typ.ElementType)
			}

			et := mod.typeString(e)
			if !elementTypeSet.has(et) {
				elementTypeSet.add(et)
				elementTypes = append(elementTypes, et)
			}
		}

		switch len(elementTypes) {
		case 1:
			return mod.typeString(t.ElementTypes[0])
		default:
			typ = "Object"
		}
	default:
		switch t {
		case schema.BoolType:
			typ = "Boolean"
		case schema.IntType:
			typ = "Integer"
		case schema.NumberType:
			typ = "Double"
		case schema.StringType:
			typ = "String"
		case schema.ArchiveType:
			typ = "Archive"
		case schema.AssetType:
			typ = "AssetOrArchive"
		case schema.JSONType:
			typ = "Object"
		case schema.AnyType:
			typ = "Object"
		}
	}
	return typ
}

func optional(t string) string {
	return fmt.Sprintf("%s<%s>", optionalType, t)
}
func input(t string) string {
	return fmt.Sprintf("%s<%s>", inputType, t)
}
func output(t string) string {
	return fmt.Sprintf("%s<%s>", outputType, t)
}
func nullable(t string) string {
	return fmt.Sprintf("@%s %s", nullableAnnot, t)
}

func getArgsClass(t string) string {
	return t + "Args"
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

func (mod *modContext) getConfigProperty(schemaType schema.Type) (string, string) {
	propertyType := mod.typeString(schemaType)

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

	return fmt.Sprintf("java.util.Optional<%v>", propertyType), getFunc
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

func printDeprecatedAttribute(w io.Writer, deprecationMessage, indent string) {
	if deprecationMessage != "" {
		fmt.Fprintf(w, "%s// %s", indent, deprecationMessage)
		fmt.Fprintf(w, "%s@deprecated\n", indent)
	}
}

func (mod *modContext) genInputProperty(pt *plainType, prop *schema.Property, inputTypeString, indent string) (string, error) {
	w := &bytes.Buffer{}
	propertyName := mod.propertyName(prop)
	propertyType := mod.typeString(prop.Type)
	backingFieldType := optional(mod.typeString(prop.Type))
	backingFieldName := propertyName
	defaultValue := "java.util.Optional.empty()"
	if prop.DefaultValue != nil {
		var err error
		defaultValue, err = mod.getDefaultValue(prop.DefaultValue, prop.Type)
		if err != nil {
			return "", err
		}
	}

	if prop.Comment != "" {
		fmt.Fprintf(w, "\n")
		printComment(w, prop.Comment, indent)
	}
	printDeprecatedAttribute(w, prop.DeprecationMessage, indent)
	fmt.Fprintf(w, "%sprivate %s %s = %s;\n", indent, backingFieldType, backingFieldName, defaultValue)
	fmt.Fprintf(w, "%spublic %s get%s() {\n", indent, propertyType, title(propertyName))
	fmt.Fprintf(w, "%s   return %s.isPresent() ? %s.get() : null;\n", indent, backingFieldName, backingFieldName)
	fmt.Fprintf(w, "%s}\n", indent)
	fmt.Fprintf(w, "%spublic %s set%s(%s %s) {\n", indent, inputTypeString, title(propertyName), propertyType, propertyName)
	fmt.Fprintf(w, "%s    this.%s = %s != null ? %s(%s) : java.util.Optional.empty();\n", indent, backingFieldName, propertyName, optionalFactory, propertyName)
	fmt.Fprintf(w, "%s    return this;\n", indent)
	fmt.Fprintf(w, "%s}\n", indent)

	// TODO secrets, state

	return w.String(), nil
}

func gradleProjectPath(packageName, fileName string) string {
	return fmt.Sprintf("lib/src/main/java/%s/%s", path.Join(strings.Split(packageName, ".")...), fileName)
}

func (mod *modContext) gen(fs fs) error {
	// var files []string
	// for p := range fs {
	// 	d := path.Dir(p)
	// 	if d == "." {
	// 		d = ""
	// 	}

	// 	if d == dir {
	// 		files = append(files, p)
	// 	}
	// }

	addFile := func(name, contents string) {
		// files = append(files, p)
		fs.add(gradleProjectPath(mod.packageName, name), []byte(contents))
	}

	// Ensure that the target module directory contains a README.md file.
	readme := mod.pkg.Description
	if readme != "" && readme[len(readme)-1] != '\n' {
		readme += "\n"
	}
	addFile("README.md", readme)

	// Utilities, config
	switch mod.mod {
	case "":
		utilities, err := mod.genUtilities()
		if err != nil {
			return err
		}
		addFile("Utilities.java", utilities)
	case "config":
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
		resource, err := mod.genResource(r)
		if err != nil {
			return err
		}
		addFile(resourceName(r)+".java", resource)

		// Generate the resource args type.
		args := &plainType{
			name:       resourceName(r) + "Args",
			baseClass:  resourceArgs,
			properties: r.InputProperties,
		}

		resourceArgs, err := mod.genInputType(args)
		if err != nil {
			return err
		}
		addFile(resourceName(r)+"Args.java", resourceArgs)
	}

	// Functions
	for _, f := range mod.functions {
		imports := map[string]codegen.StringSet{}
		mod.getImports(f, imports)

		buffer := &bytes.Buffer{}
		importStrings := pulumiImports
		for _, i := range imports {
			importStrings = append(importStrings, i.SortedValues()...)
		}
		mod.genHeader(buffer, importStrings)

		if err := mod.genFunction(buffer, f); err != nil {
			return nil
		}

		addFile(tokenToName(f.Token)+".java", buffer.String())
	}

	// Nested types
	for _, t := range mod.types {
		if mod.details(t).inputType {
			if mod.details(t).argsType {
				inputs := &plainType{
					name:       tokenToName(t.Token) + "Args",
					comment:    t.Comment,
					baseClass:  resourceArgs,
					properties: t.Properties,
				}
				input, err := mod.genInputType(inputs)
				if err != nil {
					return err
				}
				addFile(tokenToName(t.Token)+"Args.java", input)
			}
		}
		// if mod.details(t).stateType {
		// 	buffer := &bytes.Buffer{}
		// 	mod.genHeader(buffer, pulumiImports)

		// 	state, err := mod.genType(t, "", true, true, true, 1)
		// 	if err != nil {
		// 		return err
		// 	}
		// 	addFile(tokenToName(t.Token)+"GetArgs.java", state)
		// }
		if mod.details(t).outputType {
			outputs := &plainType{
				name:       tokenToName(t.Token) + "Results",
				comment:    t.Comment,
				baseClass:  resourceArgs,
				properties: t.Properties,
			}

			output, err := mod.genOutputType(outputs)
			if err != nil {
				return err
			}
			addFile(tokenToName(t.Token)+"Results.java", output)
		}
	}

	// Enums
	// TODO
	for _, en := range mod.enums {
		buffer := &bytes.Buffer{}
		mod.genHeader(buffer, []string{})

		if err := mod.genEnum(buffer, en); err != nil {
			return err
		}

		addFile(tokenToName(en.Token)+".java", buffer.String())
	}

	return nil
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

func (mod *modContext) genUtilities() (string, error) {
	w := &bytes.Buffer{}
	err := javaUtilitiesTemplate.Execute(w, javaUtilitiesTemplateContext{
		PackageName:    mod.packageName,
		PackageVersion: "20.0",
	})
	if err != nil {
		return "", err
	}

	return w.String(), nil
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

func (mod *modContext) genResource(r *schema.Resource) (string, error) {

	w := &bytes.Buffer{}

	// TODO:########
	imports := map[string]codegen.StringSet{}
	mod.getImports(r, imports)

	var additionalImports []string
	for _, i := range imports {
		additionalImports = append(additionalImports, i.SortedValues()...)
	}
	sort.Strings(additionalImports)
	importStrings := pulumiImports
	importStrings = append(importStrings, additionalImports...)
	// ##########

	mod.genHeader(w, importStrings)
	indent := "    "

	// Write the documentation comment for the resource class
	printComment(w, codegen.FilterExamples(r.Comment, "jvm"), indent)

	// Open the class
	className := resourceName(r)
	baseType := customResource
	optionsType := customResourceOptions
	switch {
	case r.IsProvider:
		baseType = providerResource
	case r.IsComponent:
		baseType = componentResource
		optionsType = componentResourceOptions
	}

	if r.DeprecationMessage != "" {
		printDeprecatedAttribute(w, r.DeprecationMessage, indent)
	}

	fmt.Fprintf(w, "%spublic class %s extends %s {\n", indent, className, baseType)

	var secretProps []string
	indent = strings.Repeat(indent, 2)
	// Emit all output properties
	for _, prop := range r.Properties {
		// Write the property attribute
		propertyName := mod.propertyName(prop)
		var propertyType string
		if prop.IsRequired {
			propertyType = output(mod.typeString(prop.Type))
		} else {
			propertyType = output(optional(mod.typeString(prop.Type)))
		}
		if prop.Secret {
			secretProps = append(secretProps, prop.Name)
		}

		if prop.Secret {
			secretProps = append(secretProps, prop.Name)
		}

		printComment(w, prop.Comment, indent)
		fmt.Fprintf(w, "%spublic %s %s;", indent, propertyType, propertyName)
		fmt.Fprintf(w, "\n")
	}
	if len(r.Properties) > 0 {
		fmt.Fprintf(w, "\n")
	}

	// Emit the class constructor
	argsType := getArgsClass(className)

	allOptionalInputs := true
	hasConstInputs := false
	for _, prop := range r.InputProperties {
		allOptionalInputs = allOptionalInputs && !prop.IsRequired
		hasConstInputs = hasConstInputs || prop.ConstValue != nil
	}

	argsParamType := argsType
	argsOverride := "args"
	if allOptionalInputs {
		// If the number of required inputs properties was zero, we can make the args object nullable.
		argsParamType = nullable(argsParamType)
		argsOverride = fmt.Sprintf("args != null ? args : new %s()", argsType)
	}
	if hasConstInputs {
		// If some args has consts value then we apply overriding function on provided object
		argsOverride = fmt.Sprintf("makeArgs(%s)", argsOverride)
	}

	optionParamType := nullable(optionsType)

	tok := r.Token
	if r.IsProvider {
		tok = mod.pkg.Name
	}

	fmt.Fprintf(w, "%s/**", indent)
	fmt.Fprintf(w, "%s* Create a %s resource with the given name, arguments, and options.\n", indent, className)
	fmt.Fprintf(w, "%s* @param name the unique name of the resource\n", indent)
	fmt.Fprintf(w, "%s* @param args The arguments used to populate this resource's properties\n", indent)
	fmt.Fprintf(w, "%s* @param options A bag of options that control this resource's behavior\n", indent)

	fmt.Fprintf(w, "%s*/\n", indent)

	fmt.Fprintf(w, "%spublic %s(String name, %s args, %s options) {\n", indent, className, argsType, optionParamType)
	indent = "            "
	if r.IsComponent {
		fmt.Fprintf(w, "%ssuper(\"%s\", name, %s, makeResourceOptions(options, \"\"), true);\n", indent, tok, argsOverride)
	} else {
		fmt.Fprintf(w, "%ssuper(\"%s\", name, %s, makeResourceOptions(options, \"\"));\n", indent, tok, argsOverride)
	}
	fmt.Fprintf(w, "        }\n")

	// Write the method that will calculate the resource arguments.
	if hasConstInputs {
		fmt.Fprintf(w, "\n")
		fmt.Fprintf(w, "    private static %s makeArgs(%s args) {", argsType, argsParamType)
		fmt.Fprintf(w, "        args = args != null ? args : new %s();\n", argsType)
		for _, prop := range r.InputProperties {
			if prop.ConstValue != nil {
				v, err := primitiveValue(prop.ConstValue)
				if err != nil {
					return "", err
				}
				fmt.Fprintf(w, "        args.%s = %s;\n", mod.propertyName(prop), v)
			}
		}
		fmt.Fprintf(w, "        return args;\n")
		fmt.Fprintf(w, "    }\n")
	}

	// Write the method that will calculate the resource options.
	fmt.Fprintf(w, "\n")
	fmt.Fprintf(w, "        private static %s makeResourceOptions(%s options, String id) {\n", optionsType, optionParamType)
	fmt.Fprintf(w, "            %s defaultOptions = new %s();\n", optionsType, optionsType)
	fmt.Fprintf(w, "            defaultOptions.Version = Utilities.Version;\n")
	if len(r.Aliases) > 0 {
		fmt.Fprintf(w, "            defaultOptions.aliases = %s(\n", listImplementationFactory)
		for _, alias := range r.Aliases {
			fmt.Fprintf(w, "                ")
			genAlias(w, alias)
			fmt.Fprintf(w, ",\n")
		}
		fmt.Fprintf(w, "            );\n")
	}
	if len(secretProps) > 0 {
		fmt.Fprintf(w, "            defaultOptions.additionalSecretOutputs = %s(\n", listImplementationFactory)
		for _, sp := range secretProps {
			fmt.Fprintf(w, "                ")
			fmt.Fprintf(w, "%q", sp)
			fmt.Fprintf(w, ",\n")
		}
		fmt.Fprintf(w, "            );\n")
	}
	fmt.Fprintf(w, "            %s merged = %s.merge(defaultOptions, options);\n", optionsType, optionsType)
	fmt.Fprintf(w, "            merged.Id = new io.pulumi.Input<String>(id);\n")
	fmt.Fprintf(w, "            return merged;\n")
	fmt.Fprintf(w, "        }\n")

	// Close the class
	fmt.Fprintf(w, "    }\n")

	return w.String(), nil
}
func genAlias(w io.Writer, alias *schema.Alias) {

	parts := []string{}

	if alias.Name != nil {
		parts = append(parts, fmt.Sprintf("\"%v\", ", *alias.Name))
	} else {
		parts = append(parts, "null, ")
	}
	if alias.Project != nil {
		parts = append(parts, fmt.Sprintf("\"%v\", ", *alias.Project))
	} else {
		parts = append(parts, "null, ")
	}
	if alias.Type != nil {
		parts = append(parts, fmt.Sprintf("\"%v\"", *alias.Type))
	} else {
		parts = append(parts, "null")
	}

	fmt.Fprintf(w, "new %s(%s)", aliasType, strings.Join(parts, ""))
}

func (mod *modContext) genFunction(w io.Writer, fun *schema.Function) error {
	return nil
}

func (mod *modContext) genEnum(w io.Writer, enum *schema.EnumType) error {
	enumName := tokenToName(enum.Token)
	underlyingType := mod.typeString(enum.ElementType)

	switch enum.ElementType {
	case schema.StringType, schema.IntType, schema.NumberType:
		printComment(w, enum.Comment, "    ")
		fmt.Fprintf(w, "    public enum %s {\n", enumName)
		for i, e := range enum.Elements {
			// If the enum doesn't have a name, set the value as the name.
			if e.Name == "" {
				e.Name = fmt.Sprintf("%v", e.Value)
			}
			safeName, err := makeSafeEnumName(e.Name, enumName)
			if err != nil {
				return err
			}
			e.Name = safeName
			if val, ok := e.Value.(string); ok {
				fmt.Fprintf(w, "        %s(%q)", e.Name, val)
			} else {
				fmt.Fprintf(w, "        %s(%v)", e.Name, e.Value)
			}
			if i < len(enum.Elements)-1 {
				fmt.Fprint(w, ",\n")
			} else {
				fmt.Fprint(w, ";\n")
			}
		}
		fmt.Fprintf(w, "        public final %s label;\n", underlyingType)
		fmt.Fprintf(w, "\n")
		fmt.Fprintf(w, "        private %s(%s label) {\n", enumName, underlyingType)
		fmt.Fprintf(w, "            this.label = label;\n")
		fmt.Fprintf(w, "        }\n")
		fmt.Fprintf(w, "    }\n")
	default:
		return errors.Errorf("enums of type %s are not yet implemented for this language", enum.ElementType.String())
	}

	return nil

}

func (mod *modContext) genInputType(pt *plainType) (string, error) {
	w := &bytes.Buffer{}

	//TODO ///
	imports := map[string]codegen.StringSet{}

	var additionalImports []string
	for _, i := range imports {
		additionalImports = append(additionalImports, i.SortedValues()...)
	}
	sort.Strings(additionalImports)
	importStrings := pulumiImports
	importStrings = append(importStrings, additionalImports...)
	// ##########
	mod.genHeader(w, importStrings)

	indent := "    "

	fmt.Fprintf(w, "\n")

	// Open the class.
	printComment(w, pt.comment, indent)
	fmt.Fprintf(w, "%spublic final class %s extends %s {\n", indent, pt.name, pt.baseClass)

	indent = strings.Repeat(indent, 2)

	// Declare each input property declaring default values when needed.
	for _, p := range pt.properties {
		prop, err := mod.genInputProperty(pt, p, pt.name, indent)
		if err != nil {
			return "", err
		}
		fmt.Fprintf(w, "%s", prop)

		if err != nil {
			return "", err
		}
		fmt.Fprintf(w, "\n")
	}
	// Generate a constructor that take required arguments
	first := true
	fmt.Fprintf(w, "%spublic %s(\n", indent, pt.name)
	for _, prop := range pt.properties {
		if prop.IsRequired {
			if first {
				first = false
			} else {
				fmt.Fprint(w, ",\n")
			}
			propertyName := mod.propertyName(prop)
			propertyType := mod.typeString(prop.Type)
			fmt.Fprintf(w, "%s    %s %s", indent, propertyType, propertyName)
		}
	}

	fmt.Fprintf(w, "\n")
	fmt.Fprintf(w, "%s    ) {\n", indent)
	for _, prop := range pt.properties {
		if prop.IsRequired {
			propertyName := mod.propertyName(prop)
			fmt.Fprintf(w, "%s        this.%s = %s(%s);\n", indent, propertyName, optionalFactory, propertyName)
		}
	}
	fmt.Fprintf(w, "%s}\n", indent)

	// Close the class.
	fmt.Fprintf(w, "%s}\n", indent)

	return w.String(), nil
}

func (mod *modContext) genOutputType(pt *plainType) (string, error) {
	w := &bytes.Buffer{}
	indent := "    "

	// TODO:########
	imports := map[string]codegen.StringSet{}

	var additionalImports []string
	for _, i := range imports {
		additionalImports = append(additionalImports, i.SortedValues()...)
	}
	sort.Strings(additionalImports)
	importStrings := pulumiImports
	importStrings = append(importStrings, additionalImports...)
	// ##########

	mod.genHeader(w, importStrings)
	fmt.Fprintf(w, "\n")

	// Open the class and attribute it appropriately.
	fmt.Fprintf(w, "%spublic final class %s {\n", indent, pt.name)

	// Generate each output field.
	for _, prop := range pt.properties {
		fieldName := mod.propertyName(prop)
		fieldType := mod.typeString(prop.Type)
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
		paramType := mod.typeString(prop.Type)

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
		fieldName := mod.propertyName(prop)
		if fieldName == paramName {
			// Avoid a no-op in case of field and property name collision.
			fieldName = "this." + fieldName
		}
		fmt.Fprintf(w, "%s        %s = %s;\n", indent, fieldName, paramName)
	}
	fmt.Fprintf(w, "%s    }\n", indent)

	// Close the class.
	fmt.Fprintf(w, "%s}\n", indent)
	return w.String(), nil
}

func printComment(w io.Writer, comment string, indent string) {
	// TODO in javadoc style
	lines := strings.Split(comment, "\n")
	for len(lines) > 0 && lines[len(lines)-1] == "" {
		lines = lines[:len(lines)-1]
	}

	if len(lines) > 0 {
		fmt.Fprintf(w, "%s/**\n", indent)
		for _, l := range lines {
			fmt.Fprintf(w, "%s* %s\n", indent, l)
		}
		fmt.Fprintf(w, "%s*/\n", indent)
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

// genGradleProject generates gradle files
func genGradleProject(pkg *schema.Package, packageName string, packageReferences map[string]string, files fs) error {
	genSettingsFile, err := genSettingsFile(pkg, packageName, packageReferences)
	if err != nil {
		return err
	}
	files.add("settings.gradle", genSettingsFile)
	genBuildFile, err := genBuildFile(pkg, packageName, packageReferences)
	if err != nil {
		return err
	}
	files.add("lib/build.gradle", genBuildFile)
	return nil
}

// genSettingsFile emits settings.gradle
func genSettingsFile(pkg *schema.Package, packageName string, packageReferences map[string]string) ([]byte, error) {
	w := &bytes.Buffer{}
	err := javaSettingsFileTemplate.Execute(w, javaSettingsFileTemplateContext{
		Package: pkg,
	})
	if err != nil {
		return nil, err
	}
	return w.Bytes(), nil
}

// genBuildFile emits build.gradle
func genBuildFile(pkg *schema.Package, packageName string, packageReferences map[string]string) ([]byte, error) {
	w := &bytes.Buffer{}
	err := javaBuildFileTemplate.Execute(w, javaBuildFileTemplateContext{})
	if err != nil {
		return nil, err
	}
	return w.Bytes(), nil
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
	modules, info, err := generateModuleContextMap(tool, pkg)
	if err != nil {
		return nil, err
	}

	className := "io.pulumi." + packageName(info.Packages, pkg.Name)

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

	// Finally emit the package metadata.
	if err := genGradleProject(pkg, className, info.PackageReferences, files); err != nil {
		return nil, err
	}
	return files, nil
}
