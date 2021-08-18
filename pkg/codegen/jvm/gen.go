package jvm

import (
	"bytes"
	"fmt"
	"github.com/pulumi/pulumi/pkg/v3/codegen"
	"github.com/pulumi/pulumi/pkg/v3/codegen/schema"
	"github.com/pulumi/pulumi/sdk/v3/go/common/util/contract"
	"path"
	"strings"
	"unicode"
)

type typeDetails struct {
	outputType bool
	inputType  bool
	stateType  bool
	argsType   bool
	plainType  bool
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

func Title(s string) string {
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

	return Title(name)
}

func tokenToName(tok string) string {
	components := strings.Split(tok, ":")
	contract.Assertf(len(components) == 3, "malformed token %v", tok)
	return Title(components[2])
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
	return Title(p.Name)
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
		getFunc = "Get"
	case schema.BoolType:
		getFunc = "GetBoolean"
	case schema.IntType:
		getFunc = "GetInt"
	case schema.NumberType:
		getFunc = "GetDouble"
	default:
		switch t := schemaType.(type) {
		case *schema.TokenType:
			if t.UnderlyingType != nil {
				return mod.getConfigProperty(t.UnderlyingType)
			}
		}

		getFunc = "GetObject<" + propertyType + ">"
		if _, ok := schemaType.(*schema.ArrayType); ok {
			return propertyType, getFunc
		}
	}

	return fmt.Sprintf("Optional<%v>", propertyType), getFunc
}

func (mod *modContext) genConfig(variables []*schema.Property) (string, error) {
	w := &bytes.Buffer{}

	fmt.Fprintf(w, "package %s;\n", mod.packageName)

	// mod.genHeader(w, []string{"Collections"})

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

	// TODO generate readme
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

	return nil
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
			csharpInfo, _ := pkg.Language["jvm"].(JavaPackageInfo)
			info = &csharpInfo
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
