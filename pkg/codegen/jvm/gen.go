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

const basePackage = "io.pulumi"

const deployment = "io.pulumi.deployment.Deployment.getInstance()"

const configFactory = "io.pulumi.Config.of"

const providerResource = basePackage + ".resources.ProviderResource"
const componentResource = basePackage + ".resources.ComponentResource"
const customResource = basePackage + ".resources.CustomResource"

const resourceArgs = basePackage + ".resources.ResourceArgs"
const invokeArgs = basePackage + ".resources.InvokeArgs"

const assetOrArchiveType = basePackage + ".core.AssetOrArchive"
const archiveType = basePackage + ".core.Archive"

const customResourceOptions = basePackage + ".resources.CustomResourceOptions"
const componentResourceOptions = basePackage + ".resources.ComponentResourceOptions"
const invokeOptions = basePackage + ".deployment.InvokeOptions"

const inputType = basePackage + ".core.Input"
const inputListType = basePackage + ".core.InputList"
const inputMapType = basePackage + ".core.InputMap"
const inputUnionType = basePackage + ".core.InputUnion"
const outputType = basePackage + ".core.Output"

const listType = "java.util.List"
const mapType = "java.util.Map"
const optionalType = "java.util.Optional"
const futureType = "java.util.concurrent.CompletableFuture"
const unionType = basePackage + ".core.Either"

const listFactory = "java.util.List.of"
const listCopyMethod = "java.util.List.copyOf"

const mapFactory = "java.util.Map.of"
const mapCopyMethod = "java.util.Map.ofEntries"

const inputFactory = "io.pulumi.core.Input.of"
const emptyInputListFactory = "io.pulumi.core.InputList.empty"
const emptyInputMapFactory = "io.pulumi.core.InputMap.empty"

const aliasFactory = basePackage + ".core.Alias.create"

const emptyFunParameter = "io.pulumi.InvokeArgs.empty"

const nullableAnnot = "javax.annotation.Nullable"
const ofNullable = "java.util.Optional.ofNullable"

type typeDetails struct {
	outputType bool
	inputType  bool
	stateType  bool
	argsType   bool
	plainType  bool
}

type plainType struct {
	name                  string
	propertyTypeQualifier string
	comment               string
	baseClass             string
	properties            []*schema.Property
	args                  bool
	state                 bool
}

type modContext struct {
	pkg           *schema.Package
	mod           string
	propertyNames map[*schema.Property]string
	types         []*schema.ObjectType
	enums         []*schema.EnumType
	resources     []*schema.Resource
	functions     []*schema.Function
	typeDetails   map[*schema.ObjectType]*typeDetails
	children      []*modContext
	tool          string
	packageName   string
	packages      map[string]string
	compatibility string
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

func (mod *modContext) isK8sCompatMode() bool {
	return mod.compatibility == "kubernetes20"
}

func (mod *modContext) isTFCompatMode() bool {
	return mod.compatibility == "tfbridge20"
}

func (mod *modContext) typeName(t *schema.ObjectType, state, input, args bool) string {
	name := tokenToName(t.Token)
	if state {
		return name + "GetArgs"
	}
	if !mod.isTFCompatMode() && !mod.isK8sCompatMode() {
		if args {
			return name + "Args"
		}
		return name
	}

	switch {
	case input:
		return name + "Args"
	case mod.details(t).plainType:
		return name + "Result"
	}
	return name
}

// Get the name for the token
func tokenToName(tok string) string {
	components := strings.Split(tok, ":")
	contract.Assertf(len(components) == 3, "malformed token %v", tok)
	return title(components[2])
}

// Get the module in which token should reside with additional qualier? TODO?? (in dotnet types qualifier was added for kubernetes)
func (mod *modContext) tokenToPackage(tok string, qualifier string) string {
	components := strings.Split(tok, ":")
	contract.Assertf(len(components) == 3, "malformed token %v", tok)

	pkg := fmt.Sprintf("%s.%s", basePackage, packageName(mod.packages, components[0]))
	pkgName := mod.pkg.TokenToModule(tok)

	if mod.isK8sCompatMode() {
		if qualifier != "" {
			return pkg + "." + packageName(mod.packages, pkgName) + "." + qualifier
		}
	}

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

func inputAnnot(name string, isRequired, json bool) string {
	nameString := fmt.Sprintf("name = %q", name)
	isRequiredString := "isRequired = "
	if isRequired {
		isRequiredString += "true"
	} else {
		isRequiredString += "false"
	}
	jsonString := "json = "
	if json {
		jsonString += "true"
	} else {
		jsonString += "false"
	}
	return fmt.Sprintf("@io.pulumi.core.internal.annotations.InputAttribute(%s, %s, %s)", nameString, isRequiredString, jsonString)
}
func outputAnnot(name string) string {
	nameString := fmt.Sprintf("name = %q", name)
	return fmt.Sprintf("@io.pulumi.core.internal.annotations.OutputAttribute(%s)", nameString)
}
func outpuTypetAnnot() string {
	return "@io.pulumi.core.internal.annotations.OutputTypeAttribute"
}
func outputConstructorAnnot() string {
	return "@io.pulumi.core.internal.annotations.OutputConstructorAttribute"
}
func resourceTypeAnnot(packageName string, name string, typ string) string {
	typString := fmt.Sprintf("typ = %q", typ)
	return fmt.Sprintf("@%s.%sResourceTypeAttribute(%s)", packageName, name, typString)
}

func (mod *modContext) typeString(t schema.Type, qualifier string, input, state, wrapInput, args, requireInitializers, optional bool) string {
	var typ string
	switch t := t.(type) {
	case *schema.EnumType:
		typ = fmt.Sprintf("%s.%s", mod.tokenToPackage(t.Token, ""), tokenToName(t.Token))
	case *schema.ArrayType:
		var listFmt string
		switch {
		case wrapInput:
			listFmt, optional = inputListType+"<%v>", false
		case requireInitializers:
			listFmt = listType + "<%v>"
		default:
			listFmt, optional = listType+"<%v>", false
		}
		wrapInput = false
		typ = fmt.Sprintf(listFmt, mod.typeString(t.ElementType, qualifier, input, state, wrapInput, args, false, false))
	case *schema.MapType:
		var mapFmt string
		switch {
		case wrapInput:
			mapFmt, optional = inputMapType+"<%v>", false
		default:
			mapFmt = mapType + "<String, %v>"
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
		if (typ == namingCtx.packageName && qualifier == "") || typ == fmt.Sprint("%s.%s", packageName, qualifier) {
			typ = qualifier
		}
		if typ != "" {
			typ += "."
		}
		typ += mod.typeName(t, state, input, args)
	case *schema.ResourceType:
		if isProvider(t) {
			typ = fmt.Sprintf("%s.Provider", mod.tokenToPackage(t.Token, ""))
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
		for _, e := range t.ElementTypes {
			// If this is an output and a "relaxed" enum, emit the type as the underlying primitive type rather than the union.
			// Eg. Output<string> rather than Output<Union<EnumType, string>>
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
			unionT := unionType
			if wrapInput {
				unionT = inputUnionType
			}
			typ = fmt.Sprintf("%s<%s>", unionT, strings.Join(elementTypes, ", "))
			wrapInput = false
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
			typ = archiveType
		case schema.AssetType:
			typ = assetOrArchiveType
		case schema.JSONType:
			typ = "Object"
		case schema.AnyType:
			typ = "Object"
		}
	}
	if wrapInput {
		typ = fmt.Sprintf("%s<%s>", inputType, typ)
	}
	if optional {
		typ = fmt.Sprintf("%s<%s>", optionalType, typ)
	}
	return typ
}

func getArgsClassName(t string) string {
	return t + "Args"
}
func getStateClassName(t string) string {
	return t + "State"
}
func getFunResultClassName(t string) string {
	return t + "Result"
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
	propertyType := mod.typeString(schemaType, "types", false, false, false /*wrapInputs*/, false /*args*/, false /*requireInitializers*/, false)

	var getFunc string

	switch schemaType {
	case schema.StringType:
		getFunc = "get"
	case schema.BoolType:
		getFunc = "getBoolean"
	case schema.IntType:
		getFunc = "getInteger"
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

	return fmt.Sprintf("%s<%s>", optionalType, propertyType), getFunc
}

func (mod *modContext) getDefaultValue(dv *schema.DefaultValue, t schema.Type) (string, error) {
	var val string
	if dv.Value != nil {
		switch enum := t.(type) {
		case *schema.EnumType:
			enumName := tokenToName(enum.Token)
			for _, e := range enum.Elements {
				if e.Value != dv.Value {
					continue
				}

				elName := e.Name
				if elName == "" {
					elName = fmt.Sprintf("%v", e.Value)
				}
				safeName, err := makeSafeEnumName(elName, enumName)
				if err != nil {
					return "", err
				}
				val = fmt.Sprintf("%s.%s.%s", mod.packageName, enumName, safeName)
				break
			}
			if val == "" {
				return "", errors.Errorf("default value '%v' not found in enum '%s'", dv.Value, enumName)
			}
		default:
			v, err := primitiveValue(dv.Value)
			if err != nil {
				return "", err
			}
			val = v
		}
	}

	if len(dv.Environment) != 0 {
		getType := ""
		switch t {
		case schema.BoolType:
			getType = "Boolean"
		case schema.IntType:
			getType = "Integer"
		case schema.NumberType:
			getType = "Double"
		}

		envVars := fmt.Sprintf("%q", dv.Environment[0])
		for _, e := range dv.Environment[1:] {
			envVars += fmt.Sprintf(", %q", e)
		}

		getEnv := fmt.Sprintf("Utilities.getEnv%s(%s)", getType, envVars)
		if val != "" {
			val = fmt.Sprintf("%s.orElse(%s)", getEnv, val)
		} else {
			val = fmt.Sprintf("%s.map(v -> %s(v))", getEnv, inputFactory)
		}
	}

	return val, nil
}

func printDeprecatedAttribute(w io.Writer, deprecationMessage, indent string) {
	if deprecationMessage != "" {
		fmt.Fprintf(w, "%s// %s", indent, deprecationMessage)
		fmt.Fprintf(w, "%s@deprecated\n", indent)
	}
}

func isNeedingBackingField(prop *schema.Property) (needsBackingField bool) {
	needsBackingField = false
	switch prop.Type.(type) {
	case *schema.ArrayType, *schema.MapType:
		needsBackingField = true
	}
	if prop.Secret {
		needsBackingField = true
	}
	return
}

func (mod *modContext) genInputProperty(pt *plainType, prop *schema.Property, res *schema.Resource, indent string) (string, error) {
	argsType := pt.args && !prop.IsPlain
	w := &bytes.Buffer{}
	propertyName := makeValidIdentifier(mod.propertyName(prop))
	propertyType := mod.typeString(prop.Type, pt.propertyTypeQualifier, true, pt.state, argsType, argsType, false, !prop.IsRequired)

	needsBackingField := isNeedingBackingField(prop)

	// Next generate the input property itself. The way this is generated depends on the type of the property:
	// complex types like lists and maps need a backing field. Secret properties also require a backing field.
	if prop.Comment != "" {
		printComment(w, prop.Comment, indent)
	}
	json := false
	if res != nil && res.IsProvider {
		json = true
		if prop.Type == schema.StringType {
			json = false
		} else if t, ok := prop.Type.(*schema.TokenType); ok && t.UnderlyingType == schema.StringType {
			json = false
		}
	}
	printDeprecatedAttribute(w, prop.DeprecationMessage, indent)
	if needsBackingField {
		fmt.Fprintf(w, "%s@%s\n", indent, nullableAnnot)
	}
	fmt.Fprintf(w, "%s%s\n", indent, inputAnnot(prop.Name, prop.IsRequired, json))
	if needsBackingField {
		requireInitializers := !pt.args || prop.IsPlain
		backingFieldType := mod.typeString(prop.Type, pt.propertyTypeQualifier, true, pt.state, argsType, argsType, requireInitializers, false)
		backingFieldName := propertyName

		fmt.Fprintf(w, "%sprivate %s %s;\n", indent, backingFieldType, backingFieldName)
		fmt.Fprintf(w, "%spublic %s get%s() {\n", indent, propertyType, title(propertyName))
		switch prop.Type.(type) {
		// Note that we use the backing field type--which is just the property type without any nullable annotation--to
		// ensure that the user does not see warnings when initializing these properties using object or collection
		// initializers.
		case *schema.ArrayType:
			var emptyFactory string
			var copyFunction string
			if argsType {
				emptyFactory = emptyInputListFactory
				copyFunction = fmt.Sprintf("%s.copy()", backingFieldName)
			} else {
				emptyFactory = listFactory
				copyFunction = fmt.Sprintf("%s(%s)", listCopyMethod, backingFieldName)
			}
			fmt.Fprintf(w, "%s    return %s == null ? %s() : %s;\n", indent, backingFieldName, emptyFactory, copyFunction)
		case *schema.MapType:
			var emptyFactory string
			var copyFunction string
			if argsType {
				emptyFactory = emptyInputMapFactory
				copyFunction = fmt.Sprintf("%s.copy()", backingFieldName)
			} else {
				emptyFactory = mapFactory
				copyFunction = fmt.Sprintf("%s(%s)", mapCopyMethod, backingFieldName)
			}
			fmt.Fprintf(w, "%s    return %s == null ? %s() : %s;\n", indent, backingFieldName, emptyFactory, copyFunction)
		default:
			fmt.Fprintf(w, "%s    return %s;\n", indent, backingFieldName)
		}
		fmt.Fprintf(w, "%s}\n", indent)
		fmt.Fprintf(w, "%spublic void set%s(%s %s) {\n", indent, title(propertyName), propertyType, propertyName)
		if prop.Secret {
			// Since we can't directly assign the Output from CreateSecret to the property, use an Output.All or
			// Output.Tuple to enable the secret flag on the data. (If any input to the All/Tuple is secret, then the
			// Output will also be secret.) //TODO this is a little hacky way
			switch t := prop.Type.(type) {
			case *schema.ArrayType:
				elementType := mod.typeString(t.ElementType, "", false, false, false, false, false, false)
				fmt.Fprintf(w, "%s        %s<%s> emptySecret = %s.ofSecret(%s());\n", indent, inputListType, elementType, inputListType, listFactory)
				fmt.Fprintf(w, "%s        %s = %s.of(%s.tuple(%s, emptySecret).apply(v -> v.t1));\n", indent, backingFieldName, inputListType, inputType, propertyName)
			case *schema.MapType:
				elementType := mod.typeString(t.ElementType, "", false, false, false, false, false, false)
				fmt.Fprintf(w, "%s        %s<%s> emptySecret = %s.ofSecret(%s());\n", indent, inputMapType, elementType, inputMapType, mapFactory)
				fmt.Fprintf(w, "%s        %s = %s.of(%s.tuple(%s, emptySecret).apply(v -> v.t1));\n", indent, backingFieldName, inputMapType, inputType, propertyName)
			default:
				elementType := mod.typeString(t, "", false, false, false, false, false, false)
				fmt.Fprintf(w, "%s        %s<%s> emptySecret = %s.ofSecret(0);\n", indent, inputType, elementType, inputType)
				fmt.Fprintf(w, "%s        %s = %s.tuple(%s, emptySecret).apply(v -> v.t1);\n", indent, backingFieldName, inputType, propertyName)
			}
		} else {
			fmt.Fprintf(w, "%s    this.%s = %s;\n", indent, backingFieldName, propertyName)
		}
		fmt.Fprintf(w, "%s}\n", indent)

	} else {
		fmt.Fprintf(w, "%spublic %s %s;\n", indent, propertyType, propertyName)
	}

	return w.String(), nil
}

func gradleProjectPath(packageName, fileName string) string {
	return fmt.Sprintf("lib/src/main/java/%s/%s", path.Join(strings.Split(packageName, ".")...), fileName)
}

func (mod *modContext) gen(fs fs) error {
	addFile := func(name, contents string) {
		if mod.mod == "config" {
			name = "config/" + name
		}
		if contents != "" {
			fs.add(gradleProjectPath(packageName(mod.packages, mod.packageName), name), []byte(contents))
		}
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
		utilitiesString, err := mod.genUtilities()
		if err != nil {
			return err
		}
		addFile("Utilities.java", utilitiesString)
		// TODO
		// annotationString, err := mod.genResourceTypeAnnot()
		// if err != nil {
		// 	return err
		// }
		// addFile(fmt.Sprintf("%sResourceTypeAttribute.java", packageName(mod.packages, mod.pkg.Name)), annotationString)
	case "config":
		if len(mod.pkg.Config) > 0 {
			configString, err := mod.genConfig(mod.pkg.Config)
			if err != nil {
				return err
			}
			addFile("Config.java", configString)
			// Emit any nested types
			for _, t := range mod.types {
				typeString, err := mod.genConfigType(t)
				if err != nil {
					return err
				}
				addFile(path.Join("types", tokenToName(t.Token)+".java"), typeString)
			}
			return nil
		}
	}

	// Resources
	for _, r := range mod.resources {
		// Generate the resource type=
		resourceString, err := mod.genResource(r)
		if err != nil {
			return err
		}
		addFile(resourceName(r)+".java", resourceString)

		// Generate the resource args type.
		// Arguments are in a different namespace for the Kubernetes SDK.
		qualifier := ""
		if mod.isK8sCompatMode() && !r.IsProvider {
			qualifier = "inputs"
		}
		argsClassName := getArgsClassName(resourceName(r))
		argsType := &plainType{
			name:                  argsClassName,
			baseClass:             resourceArgs,
			properties:            r.InputProperties,
			propertyTypeQualifier: "inputs",
			args:                  true,
		}
		argsTypeString, err := mod.genInputType(argsType, r, qualifier)
		if err != nil {
			return err
		}
		addFile(path.Join(qualifier, argsClassName+".java"), argsTypeString)

		// Generate the `Get` args type, if any.
		if r.StateInputs != nil {
			stateClassName := getStateClassName(resourceName(r))
			stateType := &plainType{
				name:                  stateClassName,
				baseClass:             resourceArgs,
				properties:            r.StateInputs.Properties,
				propertyTypeQualifier: "inputs",
				args:                  true,
				state:                 true,
			}
			stateTypeString, err := mod.genInputType(stateType, r, qualifier)
			if err != nil {
				return err
			}
			addFile(path.Join(qualifier, stateClassName+".java"), stateTypeString)
		}
	}

	// Functions
	for _, f := range mod.functions {
		funString, err := mod.genFunction(f)
		if err != nil {
			return nil
		}
		addFile(tokenToName(f.Token)+".java", funString)

		// Emit the args and result types, if any.
		if f.Inputs != nil {
			args := &plainType{
				name:                  getArgsClassName(tokenToName(f.Token)),
				baseClass:             invokeArgs,
				propertyTypeQualifier: "inputs",
				properties:            f.Inputs.Properties,
			}
			argsTypeString, err := mod.genInputType(args, nil, "")
			if err != nil {
				return err
			}
			addFile(getArgsClassName(tokenToName(f.Token))+".java", argsTypeString)
		}
		if f.Outputs != nil {
			res := &plainType{
				name:                  getFunResultClassName(tokenToName(f.Token)),
				propertyTypeQualifier: "outputs",
				properties:            f.Outputs.Properties,
			}
			resultTypeString, err := mod.genOutputType(res, "")
			if err != nil {
				return err
			}
			addFile(getFunResultClassName(tokenToName(f.Token))+".java", resultTypeString)
		}
	}

	// Nested types
	for _, t := range mod.types {
		if mod.details(t).inputType {
			if mod.details(t).argsType {
				name := mod.typeName(t, false, true, true)
				inputs := &plainType{
					name:                  name,
					comment:               t.Comment,
					baseClass:             resourceArgs,
					properties:            t.Properties,
					state:                 false,
					args:                  true,
					propertyTypeQualifier: "inputs",
				}
				input, err := mod.genInputType(inputs, nil, "inputs")
				if err != nil {
					return err
				}
				addFile(path.Join("inputs", name+".java"), input)
			}
			if mod.details(t).plainType {
				name := mod.typeName(t, false, true, false)
				inputs := &plainType{
					name:                  name,
					comment:               t.Comment,
					baseClass:             resourceArgs,
					properties:            t.Properties,
					state:                 false,
					args:                  false,
					propertyTypeQualifier: "inputs",
				}
				input, err := mod.genInputType(inputs, nil, "inputs")
				if err != nil {
					return err
				}
				addFile(path.Join("inputs", name+".java"), input)
			}
		}
		if mod.details(t).stateType {
			name := mod.typeName(t, true, true, true)
			inputs := &plainType{
				name:                  name,
				comment:               t.Comment,
				baseClass:             resourceArgs,
				properties:            t.Properties,
				state:                 true,
				args:                  true,
				propertyTypeQualifier: "inputs",
			}
			input, err := mod.genInputType(inputs, nil, "inputs")
			if err != nil {
				return err
			}
			addFile(path.Join("inputs", name+".java"), input)
		}
		if mod.details(t).outputType {
			name := mod.typeName(t, false, false, false)
			inputs := &plainType{
				name:                  name,
				comment:               t.Comment,
				baseClass:             resourceArgs,
				properties:            t.Properties,
				state:                 false,
				args:                  false,
				propertyTypeQualifier: "outputs",
			}
			input, err := mod.genOutputType(inputs, "outputs")
			if err != nil {
				return err
			}
			suffix := ""
			if (mod.isTFCompatMode() || mod.isK8sCompatMode()) && mod.details(t).plainType {
				suffix = "Result"
			}
			addFile(path.Join("outputs", name+suffix+".java"), input)
		}
	}

	// Enums
	for _, en := range mod.enums {
		enumString, err := mod.genEnum(en)
		if err != nil {
			return err
		}
		addFile(tokenToName(en.Token)+".java", enumString)
	}

	return nil
}

func (mod *modContext) genHeader(w io.Writer, imports []string, qualifier string) {
	fmt.Fprintf(w, "// *** WARNING: this file was generated by %v. ***\n", mod.tool)
	fmt.Fprintf(w, "// *** Do not edit by hand unless you're certain you know what you are doing! ***\n")
	fmt.Fprintf(w, "\n")

	packageName := mod.packageName
	if qualifier != "" {
		packageName = packageName + "." + qualifier
	}
	fmt.Fprintf(w, "package %s;\n", packageName)

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
		PackageName: mod.packageName,
	})
	if err != nil {
		return "", err
	}

	return w.String(), nil
}

func (mod *modContext) genResourceTypeAnnot() (string, error) {
	w := &bytes.Buffer{}
	err := javaResourceTypeAnnotTemplate.Execute(w, javaResourceTypeAnnotTemplateContext{
		PackageName: mod.packageName,
		Name:        packageName(mod.packages, mod.pkg.Name),
	})
	if err != nil {
		return "", err
	}

	return w.String(), nil
}

func (mod *modContext) genConfig(variables []*schema.Property) (string, error) {
	w := &bytes.Buffer{}

	mod.genHeader(w, []string{}, "config")

	fmt.Fprintf(w, "public class Config {\n")
	fmt.Fprintf(w, "\tprivate final static %s.Config config = %s(\"%v\");\n", basePackage, configFactory, mod.pkg.Name)

	// Generate Java getters and setters
	for _, p := range variables {
		propertyType, getFunc := mod.getConfigProperty(p.Type)
		propertyName := mod.propertyName(p)

		defaultValue := "null"
		if p.DefaultValue != nil {
			dv, err := mod.getDefaultValue(p.DefaultValue, p.Type)
			if err != nil {
				return "", err
			}
			defaultValue = dv

		}

		printComment(w, p.Comment, "    ")
		fmt.Fprintf(w, "\tprivate static %s %sStore;\n", propertyType, propertyName)
		fmt.Fprintf(w, "\tstatic {\n")
		fmt.Fprintf(w, "\t\t%sStore = config.%s(\"%s\");\n", propertyName, getFunc, p.Name)
		fmt.Fprintf(w, "\t\t%sStore = %sStore != null ? %sStore : %s;\n", propertyName, propertyName, propertyName, defaultValue)
		fmt.Fprintf(w, "\t}\n")
		fmt.Fprintf(w, "\tpublic static %s get%s() { return %sStore; }\n", propertyType, title(propertyName), propertyName)
		fmt.Fprintf(w, "\tpublic static void set%s(%s %s) { %sStore = %s; }\n", title(propertyName), propertyType, propertyName, propertyName, propertyName)

	}

	fmt.Fprintf(w, "}")

	return w.String(), nil
}

func (mod *modContext) genConfigType(t *schema.ObjectType) (string, error) {
	w := &bytes.Buffer{}

	mod.genHeader(w, []string{}, "")

	fmt.Fprintf(w, "public class %s {\n", tokenToName(t.Token))
	fmt.Fprintf(w, "\tprivate final static pulumi.Config config = new io.pulumi.Config(\"%v\");\n", mod.pkg.Name)

	// Generate each output field.
	for _, prop := range t.Properties {
		name := mod.propertyName(prop)
		typ := mod.typeString(prop.Type, "types", false, false, false /*wrapInput*/, false /*args*/, false, !prop.IsRequired)

		// TODO
		initializer := ""
		// if !prop.IsRequired && !isValueType(prop.Type) && !isImmutableArrayType(prop.Type, false) {
		// 	initializer = " = null"
		// }

		printComment(w, prop.Comment, "    ")
		fmt.Fprintf(w, "    public %s %s%s;\n", typ, name, initializer)
	}

	fmt.Fprintf(w, "}")

	return w.String(), nil
}

func (mod *modContext) genResource(r *schema.Resource) (string, error) {

	w := &bytes.Buffer{}

	// TODO: create a good way to specify imports?
	imports := map[string]codegen.StringSet{}
	mod.getImports(r, imports)

	var additionalImports []string
	for _, i := range imports {
		additionalImports = append(additionalImports, i.SortedValues()...)
	}
	sort.Strings(additionalImports)
	importStrings := pulumiImports
	importStrings = append(importStrings, additionalImports...)

	mod.genHeader(w, importStrings, "")

	// Write the documentation comment for the resource class
	printComment(w, codegen.FilterExamples(r.Comment, "jvm"), "")

	// Open the class
	className := resourceName(r)
	baseType := customResource
	optionsType := customResourceOptions
	switch {
	case r.IsProvider:
		baseType = providerResource
	case mod.isK8sCompatMode():
		baseType = "io.pulumi.kubernetes.KubernetesResource"
	case r.IsComponent:
		baseType = componentResource
		optionsType = componentResourceOptions
	}

	if r.DeprecationMessage != "" {
		printDeprecatedAttribute(w, r.DeprecationMessage, "")
	}

	// fmt.Fprintf(w, "%s\n", resourceTypeAnnot(mod.packageName, packageName(mod.packages, mod.pkg.Name), r.Token))
	fmt.Fprintf(w, "public class %s extends %s {\n", className, baseType)

	var secretProps []string
	// Emit all output properties
	for _, prop := range r.Properties {
		// Write the property attribute
		propertyName := makeValidIdentifier(mod.propertyName(prop))

		required := prop.IsRequired || mod.isK8sCompatMode()
		var propertyType string = mod.typeString(prop.Type, "outputs", false, false, false, false, false, !required)
		// Workaround the fact that provider inputs come back as strings.
		if r.IsProvider && !schema.IsPrimitiveType(prop.Type) {
			propertyType = "String"
			if !prop.IsRequired {
				propertyType = fmt.Sprintf("%s(%s)", optionalType, propertyType)
			}
		}

		if prop.Secret {
			secretProps = append(secretProps, prop.Name)
		}

		printComment(w, prop.Comment, "    ")
		fmt.Fprintf(w, "    %s\n", outputAnnot(prop.Name))
		fmt.Fprintf(w, "    public final %s<%s> %s = null;", outputType, propertyType, propertyName)
		fmt.Fprintf(w, "\n")
	}
	if len(r.Properties) > 0 {
		fmt.Fprintf(w, "\n")
	}

	// Emit the class constructor
	argsType := getArgsClassName(className)
	if mod.isK8sCompatMode() && !r.IsProvider {
		argsType = fmt.Sprintf("%s.%sArgs", mod.tokenToPackage(r.Token, "inputs"), className)
	}

	allOptionalInputs := true
	hasConstInputs := false
	for _, prop := range r.InputProperties {
		allOptionalInputs = allOptionalInputs && !prop.IsRequired
		hasConstInputs = hasConstInputs || prop.ConstValue != nil
	}

	argsParamType := argsType
	argsOverride := "args"
	if allOptionalInputs || mod.isK8sCompatMode() {
		// If the number of required inputs properties was zero, we can make the args object nullable.
		argsParamType = fmt.Sprintf("@%s %s", nullableAnnot, argsParamType)
		var nulls string
		join := ""
		for range r.InputProperties {
			nulls = nulls + join + "null"
			join = ", "
		}
		argsOverride = fmt.Sprintf("args != null ? args : new %s(%s)", argsType, nulls)
	}
	if hasConstInputs {
		// If some args has consts value then we apply overriding function on provided object
		argsOverride = "makeArgs(args)"
	}

	optionParamType := fmt.Sprintf("@%s %s", nullableAnnot, optionsType)

	tok := r.Token
	if r.IsProvider {
		tok = mod.pkg.Name
	}

	fmt.Fprintf(w, "    /**\n")
	fmt.Fprintf(w, "    * Create a %s resource with the given name, arguments, and options.\n", className)
	fmt.Fprintf(w, "    * @param name the unique name of the resource\n")
	fmt.Fprintf(w, "    * @param args The arguments used to populate this resource's properties\n")
	fmt.Fprintf(w, "    * @param options A bag of options that control this resource's behavior\n")
	fmt.Fprintf(w, "    */\n")

	fmt.Fprintf(w, "    public %s(String name, %s args, %s options) {\n", className, argsParamType, optionParamType)
	if r.IsComponent {
		fmt.Fprintf(w, "        super(\"%s\", name, %s, makeResourceOptions(options, %s(\"\")), true);\n", tok, argsOverride, inputFactory)
	} else {
		fmt.Fprintf(w, "        super(\"%s\", name, %s, makeResourceOptions(options, %s(\"\")));\n", tok, argsOverride, inputFactory)
	}
	fmt.Fprintf(w, "    }\n")

	// Write a private constructor for the use of `Get`.
	if !r.IsProvider && !r.IsComponent {
		stateParam := ""
		stateRef := "null"
		if r.StateInputs != nil {
			stateType := fmt.Sprintf("@%s %s", nullableAnnot, getStateClassName(className))
			stateParam = fmt.Sprintf("%s state, ", stateType)
			stateRef = "state"
		}

		fmt.Fprintf(w, "\n")
		fmt.Fprintf(w, "    private %s(String name, %s<String> id, %s@%s %s options) {\n", className, inputType, stateParam, nullableAnnot, optionsType)
		fmt.Fprintf(w, "        super(\"%s\", name, %s, makeResourceOptions(options, id));\n", tok, stateRef)
		fmt.Fprintf(w, "    }\n")
	}

	// Write the method that will calculate the resource arguments.
	if hasConstInputs {
		fmt.Fprintf(w, "\n")
		fmt.Fprintf(w, "    private static %s makeArgs(%s args) {\n", argsType, argsParamType)
		fmt.Fprintf(w, "        args = args != null ? args : new %s(", argsType)
		if len(r.InputProperties) > 0 {
			init := "\n"
			for _, prop := range r.InputProperties {
				if prop.ConstValue != nil {
					v, err := primitiveValue(prop.ConstValue)
					if err != nil {
						return "", err
					}
					fmt.Fprintf(w, "%s            %s(%s)", init, inputFactory, v)
				} else {
					fmt.Fprintf(w, "%s            null", init)
				}
				init = ",\n"
			}
			fmt.Fprintf(w, "\n        );\n")
		} else {
			fmt.Fprintf(w, ");\n")
		}
		fmt.Fprintf(w, "        return args;\n")
		fmt.Fprintf(w, "    }\n")
	}

	// Write the method that will calculate the resource options. //TODO this is probably  wrong, maybe merge and then create new object based on it with changed version, aliases, id, secrets etc
	fmt.Fprintf(w, "\n")
	fmt.Fprintf(w, "    private static %s makeResourceOptions(%s options, @%s %s<String> id) {\n", optionsType, optionParamType, nullableAnnot, inputType)
	fmt.Fprintf(w, "        return new %s(\n", optionsType)
	fmt.Fprintf(w, "            id != null ? id : options.getId().orElse(null),\n")
	fmt.Fprintf(w, "            null,\n")
	fmt.Fprintf(w, "            null,\n")
	fmt.Fprintf(w, "            false,\n")
	fmt.Fprintf(w, "            null,\n")
	fmt.Fprintf(w, "            options.getVersion().isPresent() ? options.getVersion().get() : \"\",\n") //Utilities.version,\n")
	fmt.Fprintf(w, "            null,\n")
	fmt.Fprintf(w, "            null,\n")
	fmt.Fprintf(w, "            null,\n")
	if len(r.Aliases) > 0 {
		init := "\n"
		fmt.Fprintf(w, "            %s(", listFactory)
		for _, alias := range r.Aliases {

			fmt.Fprintf(w, "%s                ", init)
			printAlias(w, alias)
			init = ",\n"
		}
		fmt.Fprintf(w, "\n            ),\n")
	} else {
		fmt.Fprintf(w, "            null,\n")
	}
	fmt.Fprintf(w, "            null,\n")
	fmt.Fprintf(w, "            false,\n") //TODO: is false a default value?
	if len(secretProps) > 0 {
		fmt.Fprintf(w, "            %s(\n", listFactory)
		first := true
		for _, sp := range secretProps {
			if first {
				first = false
			} else {
				fmt.Fprintf(w, ",\n")
			}
			fmt.Fprintf(w, "                ")
			fmt.Fprintf(w, "%q", sp)
		}
		fmt.Fprintf(w, "\n            ),\n")
	} else {
		fmt.Fprintf(w, "            null,\n")
	}
	fmt.Fprintf(w, "            null\n")
	fmt.Fprintf(w, "        );\n")
	fmt.Fprintf(w, "    }\n")

	// Write the `Get` method for reading instances of this resource unless this is a provider resource or ComponentResource.
	if !r.IsProvider && !r.IsComponent {
		fmt.Fprintf(w, "    /**\n")
		fmt.Fprintf(w, "    * Get an existing %s resource's state with the given name, ID, and optional extra\n", className)
		fmt.Fprintf(w, "    * properties used to qualify the lookup.\n")
		fmt.Fprintf(w, "    * @param name The unique name of the resulting resource.\n")
		fmt.Fprintf(w, "    * @param id The unique provider ID of the resource to lookup.\n")

		stateParam := ""
		stateRef := ""
		if r.StateInputs != nil {
			stateType := fmt.Sprintf("@%s %s", nullableAnnot, getStateClassName(className))
			stateParam = fmt.Sprintf("%s state, ", stateType)
			stateRef = "state, "
			fmt.Fprintf(w, "    * @param state Any extra arguments used during the lookup.\n")
		}

		fmt.Fprintf(w, "    * @param options A bag of options that control this resource's behavior.\n")
		fmt.Fprintf(w, "    */\n")
		fmt.Fprintf(w, "    public static %s get(String name, %s<String> id, %s@%s %s options) {\n", className, inputType, stateParam, nullableAnnot, optionsType)
		fmt.Fprintf(w, "        return new %s(name, id, %soptions);\n", className, stateRef)
		fmt.Fprintf(w, "    }\n")
	}

	// Close the class
	fmt.Fprintf(w, "}\n")

	return w.String(), nil
}
func printAlias(w io.Writer, alias *schema.Alias) {

	parts := []string{}

	if alias.Name != nil {
		parts = append(parts, fmt.Sprintf("%s(\"%v\")", inputFactory, *alias.Name))
	} else {
		parts = append(parts, "null")
	}
	if alias.Project != nil {
		parts = append(parts, fmt.Sprintf("%s(\"%v\")", inputFactory, *alias.Project))
	} else {
		parts = append(parts, "null")
	}
	if alias.Type != nil {
		parts = append(parts, fmt.Sprintf("%s(\"%v\")", inputFactory, *alias.Type))
	} else {
		parts = append(parts, "null")
	}
	parts = append(parts, "null")
	parts = append(parts, fmt.Sprintf("(%s<String>) null", inputType))

	fmt.Fprintf(w, "%s(%s(%s))", inputFactory, aliasFactory, strings.Join(parts, ", "))
}

func (mod *modContext) genFunction(fun *schema.Function) (string, error) {
	imports := map[string]codegen.StringSet{}
	mod.getImports(fun, imports)

	w := &bytes.Buffer{}
	importStrings := pulumiImports
	for _, i := range imports {
		importStrings = append(importStrings, i.SortedValues()...)
	}
	mod.genHeader(w, importStrings, "")

	className := tokenToName(fun.Token)

	var typeParameter string
	var returnType string
	if fun.Outputs != nil {
		typeParameter = fmt.Sprintf("<%s>", getFunResultClassName(className))
		returnType = futureType + typeParameter
	}

	var argsParamDef string
	argsParamRef := emptyFunParameter
	if fun.Inputs != nil {
		allOptionalInputs := true
		for _, prop := range fun.Inputs.Properties {
			allOptionalInputs = allOptionalInputs && !prop.IsRequired
		}

		argsParamDef = fmt.Sprintf("%sArgs args, ", className)
		argsParamRef = "args"

		if allOptionalInputs {
			// If the number of required input properties was zero, we can make the args object optional.
			argsParamDef = fmt.Sprintf("@%s %s", nullableAnnot, argsParamDef)
			argsParamRef = fmt.Sprintf("args != null ? args : new %sArgs()", className)
		}
	}

	// Emit the doc comment, if any.
	printComment(w, fun.Comment, "        ")
	printDeprecatedAttribute(w, fun.DeprecationMessage, "    ")

	// Open the class we'll use for datasources.
	fmt.Fprintf(w, "public class %s {\n", className)

	// Emit the datasource method.
	fmt.Fprintf(w, "    public static %s invokeAsync(%s%s options) {\n", returnType, argsParamDef, invokeOptions)
	fmt.Fprintf(w, "        return %s.%sinvokeAsync(\"%s\", %s, options);\n", deployment, typeParameter, fun.Token, argsParamRef) //TODO in dotnet this was options.withVersion
	fmt.Fprintf(w, "    }\n")

	// Close the class.
	fmt.Fprintf(w, "}\n")

	return w.String(), nil
}

func (mod *modContext) genEnum(enum *schema.EnumType) (string, error) {
	imports := map[string]codegen.StringSet{}
	mod.getImports(enum, imports)

	w := &bytes.Buffer{}
	importStrings := pulumiImports
	for _, i := range imports {
		importStrings = append(importStrings, i.SortedValues()...)
	}
	mod.genHeader(w, importStrings, "")

	enumName := tokenToName(enum.Token)
	underlyingType := mod.typeString(enum.ElementType, "", false, false, false, false, false, false)

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
				return "", err
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
		return "", errors.Errorf("enums of type %s are not yet implemented for this language", enum.ElementType.String())
	}

	return w.String(), nil

}

// Set to avoid generating a class with the same name twice.
var generatedTypes = codegen.Set{}

func (mod *modContext) genInputType(pt *plainType, res *schema.Resource, qualifierOverride string) (string, error) {
	// The way the legacy codegen for kubernetes is structured, inputs for a resource args type and resource args
	// subtype could become a single class because of the name + namespace clash. We use a set of generated types
	// to prevent generating classes with equal full names in multiple files. The check should be removed if we
	// ever change the namespacing in the k8s SDK to the standard one.
	if mod.isK8sCompatMode() {
		key := mod.packageName + pt.name
		if generatedTypes.Has(key) {
			return "", nil
		}
		generatedTypes.Add(key)
	}
	w := &bytes.Buffer{}

	imports := map[string]codegen.StringSet{}

	var additionalImports []string
	for _, i := range imports {
		additionalImports = append(additionalImports, i.SortedValues()...)
	}
	sort.Strings(additionalImports)
	importStrings := pulumiImports
	importStrings = append(importStrings, additionalImports...)

	mod.genHeader(w, importStrings, qualifierOverride)

	fmt.Fprintf(w, "\n")

	final := "final "
	if mod.isK8sCompatMode() && (res == nil || !res.IsProvider) {
		final = ""
	}
	// Open the class.
	printComment(w, pt.comment, "")
	fmt.Fprintf(w, "public %sclass %s extends %s {\n", final, pt.name, pt.baseClass)

	// Declare each input property declaring default values when needed.
	for _, p := range pt.properties {
		prop, err := mod.genInputProperty(pt, p, res, "    ")
		if err != nil {
			return "", err
		}
		fmt.Fprintf(w, "%s", prop)

		if err != nil {
			return "", err
		}
		fmt.Fprintf(w, "\n")
	}
	// Generate a constructor that will set default values.
	fmt.Fprintf(w, "    public %s(", pt.name)
	if len(pt.properties) > 0 {
		init := "\n"
		for _, prop := range pt.properties {
			propertyName := makeValidIdentifier(mod.propertyName(prop))
			needsBackingField := isNeedingBackingField(prop)
			argsType := pt.args && !prop.IsPlain
			propertyType := mod.typeString(prop.Type, pt.propertyTypeQualifier, true, pt.state, argsType, argsType, false, false)
			if !prop.IsRequired || needsBackingField {
				propertyType = fmt.Sprintf("@%s %s", nullableAnnot, propertyType)
			}
			fmt.Fprintf(w, "%s        %s %s", init, propertyType, propertyName)
			init = ",\n"
		}
		fmt.Fprintf(w, "\n    ) {\n")
		for _, prop := range pt.properties {
			propertyName := makeValidIdentifier(mod.propertyName(prop))

			needsBackingField := isNeedingBackingField(prop)

			if prop.DefaultValue != nil {
				dv, err := mod.getDefaultValue(prop.DefaultValue, prop.Type)
				if err != nil {
					return "", err
				}
				guardedPropertyName := propertyName

				if !prop.IsRequired && !needsBackingField {
					guardedPropertyName = fmt.Sprintf("%s(%s)", ofNullable, guardedPropertyName)
				}
				fmt.Fprintf(w, "        this.%s = %s != null ? %s : %s;\n", propertyName, propertyName, guardedPropertyName, dv)
			} else {
				guardedPropertyName := propertyName
				if !prop.IsRequired && !needsBackingField {
					guardedPropertyName = fmt.Sprintf("%s(%s)", ofNullable, guardedPropertyName)
				}
				fmt.Fprintf(w, "        this.%s = %s;\n", propertyName, guardedPropertyName)
			}
		}
		fmt.Fprintf(w, "\n}")
	} else {
		fmt.Fprintf(w, ") {}\n")
	}

	// Close the class.
	fmt.Fprintf(w, "}\n")

	return w.String(), nil
}

func (mod *modContext) genOutputType(pt *plainType, qualifierOverride string) (string, error) {
	w := &bytes.Buffer{}

	imports := map[string]codegen.StringSet{}

	var additionalImports []string
	for _, i := range imports {
		additionalImports = append(additionalImports, i.SortedValues()...)
	}
	sort.Strings(additionalImports)
	importStrings := pulumiImports
	importStrings = append(importStrings, additionalImports...)

	mod.genHeader(w, importStrings, qualifierOverride)
	fmt.Fprintf(w, "\n")

	// Open the class and attribute it appropriately.
	fmt.Fprintf(w, "%s\n", outpuTypetAnnot())
	fmt.Fprintf(w, "public final class %s {\n", pt.name)

	// Generate each output field.
	for _, prop := range pt.properties {
		fieldName := makeValidIdentifier(mod.propertyName(prop))
		required := prop.IsRequired || mod.isK8sCompatMode()
		fieldType := mod.typeString(prop.Type, pt.propertyTypeQualifier, false, false, false, false, true, !required)
		printComment(w, prop.Comment, "    ")
		fmt.Fprintf(w, "    public final %s %s;\n", fieldType, fieldName)
	}
	if len(pt.properties) > 0 {
		fmt.Fprintf(w, "\n")
	}

	// Generate an appropriately-attributed constructor that will set this types' fields.
	fmt.Fprintf(w, "    %s\n", outputConstructorAnnot())
	fmt.Fprintf(w, "    private %s(", pt.name)

	// Generate the constructor parameters.
	if len(pt.properties) > 0 {
		init := "\n"
		for _, prop := range pt.properties {
			paramName := makeValidIdentifier(mod.propertyName(prop))
			required := prop.IsRequired || mod.isK8sCompatMode()
			paramType := mod.typeString(prop.Type, pt.propertyTypeQualifier, false, false, false, false, false, false)
			if !required {
				paramType = fmt.Sprintf("@%s %s", nullableAnnot, paramType)
			}

			fmt.Fprintf(w, "%s        %s %s", init, paramType, paramName)
			init = ",\n"
		}

		// Generate the constructor body.
		fmt.Fprintf(w, "\n    ) {\n")
		for _, prop := range pt.properties {
			paramName := makeValidIdentifier(prop.Name)
			fieldName := "this." + makeValidIdentifier(mod.propertyName(prop))
			required := prop.IsRequired || mod.isK8sCompatMode()
			if !required {
				fmt.Fprintf(w, "        %s = %s(%s);\n", fieldName, ofNullable, paramName)
			} else {
				fmt.Fprintf(w, "        %s = %s;\n", fieldName, paramName)
			}
		}
		fmt.Fprintf(w, "    }\n")
	} else {
		fmt.Fprintf(w, ") {}\n")
	}

	// Close the class.
	fmt.Fprintf(w, "}\n")
	return w.String(), nil
}

func printComment(w io.Writer, comment string, indent string) {
	comment = strings.ReplaceAll(comment, "*/", "*&#47")
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
				pkg:           p,
				mod:           modName,
				tool:          tool,
				packageName:   pkgName,
				packages:      info.Packages,
				typeDetails:   details,
				propertyNames: propertyNames,
				compatibility: info.Compatibility,
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
		PackageName: packageName,
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
