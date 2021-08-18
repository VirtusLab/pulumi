package jvm

import (
	"bytes"
	"fmt"
	"io"
	"strings"

	"github.com/hashicorp/hcl/v2"
	"github.com/pulumi/pulumi/pkg/v3/codegen"
	"github.com/pulumi/pulumi/pkg/v3/codegen/hcl2"
	"github.com/pulumi/pulumi/pkg/v3/codegen/hcl2/model"
	"github.com/pulumi/pulumi/pkg/v3/codegen/hcl2/model/format"
	"github.com/pulumi/pulumi/pkg/v3/codegen/hcl2/syntax"
	"github.com/pulumi/pulumi/pkg/v3/codegen/schema"
	"github.com/pulumi/pulumi/sdk/v3/go/common/util/contract"
)

type generator struct {
	// The formatter to use when generating code.
	*format.Formatter
	program *hcl2.Program
	// Java packages
	packages map[string]map[string]string
	// Java codegen compatibility mode per package.
	compatibilities map[string]string
	// A function to convert tokens to module names per package (utilizes the `moduleFormat` setting internally).
	tokenToModules map[string]func(x string) string
	// Type names per invoke function token.
	functionArgs map[string]string
	// Whether awaits are needed, and therefore an async Initialize method should be declared.
	futureInit    bool
	configCreated bool
	diagnostics   hcl.Diagnostics
}

const pulumiPackage = "pulumi"

func GenerateProgram(program *hcl2.Program) (map[string][]byte, hcl.Diagnostics, error) {
	// Linearize the nodes into an order appropriate for procedural code generation.
	nodes := hcl2.Linearize(program)

	// Import Java specific schema info.
	packages := make(map[string]map[string]string)
	compatibilities := make(map[string]string)
	tokenToModules := make(map[string]func(x string) string)
	functionArgs := make(map[string]string)
	for _, p := range program.Packages() {
		if err := p.ImportLanguages(map[string]schema.Language{"jvm": Importer}); err != nil {
			return make(map[string][]byte), nil, err
		}

		javaInfo := p.Language["jvm"].(JavaPackageInfo)
		packageNamespaces := javaInfo.Packages
		packages[p.Name] = packageNamespaces
		compatibilities[p.Name] = javaInfo.Compatibility
		tokenToModules[p.Name] = p.TokenToModule

		for _, f := range p.Functions {
			if f.Inputs != nil {
				functionArgs[f.Inputs.Token] = f.Token
			}
		}
	}

	g := &generator{
		program:         program,
		packages:        packages,
		compatibilities: compatibilities,
		tokenToModules:  tokenToModules,
		functionArgs:    functionArgs,
	}
	g.Formatter = format.NewFormatter(g)

	// TODO async methods using java Thread

	for _, n := range nodes {
		if r, ok := n.(*hcl2.Resource); ok && requiresAsyncInit(r) {
			g.futureInit = true
			break
		}
	}

	var index bytes.Buffer
	g.genPreamble(&index, program)

	g.Indented(func() {
		// Emit async Initialize if needed
		if g.futureInit {
			g.genInitialize(&index, nodes)
		}

		g.Indented(func() {
			for _, n := range nodes {
				g.genNode(&index, n)
			}
		})
	})
	g.genPostamble(&index, nodes)

	files := map[string][]byte{
		"MyStack.java": index.Bytes(),
	}
	return files, g.diagnostics, nil
}

// genTrivia generates the list of trivia associated with a given token.
func (g *generator) genTrivia(w io.Writer, token syntax.Token) {
	for _, t := range token.LeadingTrivia {
		if c, ok := t.(syntax.Comment); ok {
			g.genComment(w, c)
		}
	}
	for _, t := range token.TrailingTrivia {
		if c, ok := t.(syntax.Comment); ok {
			g.genComment(w, c)
		}
	}
}

// genComment generates a comment into the output.
func (g *generator) genComment(w io.Writer, comment syntax.Comment) {
	for _, l := range comment.Lines {
		g.Fgenf(w, "%s//%s\n", g.Indent, l)
	}
}

// genPreamble generates using statements, class definition and constructor.
func (g *generator) genPreamble(w io.Writer, program *hcl2.Program) {
	// Accumulate other using statements for the various providers and packages. Don't emit them yet, as we need
	// to sort them later on.
	standardImports := codegen.NewStringSet()
	pulumiImports := codegen.NewStringSet()
	for _, n := range program.Nodes {
		if r, isResource := n.(*hcl2.Resource); isResource {
			pkg, _, _, _ := r.DecomposeToken()
			if pkg != pulumiPackage {
				namespace := packageName(g.packages[pkg], pkg)
				pulumiImports.Add(fmt.Sprintf("io.pulumi.%[1]s", namespace))
			}

			if r.Options != nil && r.Options.Range != nil {
				standardImports.Add("java.util.*")
			}
		}
		diags := n.VisitExpressions(nil, func(n model.Expression) (model.Expression, hcl.Diagnostics) {
			if call, ok := n.(*model.FunctionCallExpression); ok {
				for _, i := range g.genFunctionUsings(call) {
					if strings.HasPrefix(i, "java") {
						standardImports.Add(i)
					} else {
						pulumiImports.Add(i)
					}
				}
			}
			// TODO splat expressions?
			//if _, ok := n.(*model.SplatExpression); ok {
			//	standardImports.Add("System.Linq")
			//}
			return n, nil
		})
		contract.Assert(len(diags) == 0)
	}

	if g.futureInit {
		standardImports.Add("java.util.concurrent.CompletableFuture")
	}

	for _, pkg := range standardImports.SortedValues() {
		g.Fprintf(w, "import %v;\n", pkg)
	}
	g.Fprintln(w, `import io.pulumi;`)
	for _, pkg := range pulumiImports.SortedValues() {
		g.Fprintf(w, "import %v;\n", pkg)
	}

	g.Fprint(w, "\n")

	// Emit Stack class signature
	g.Fprint(w, "public class MyStack extends Stack\n")
	g.Fprint(w, "{\n")
	g.Fprint(w, "    public MyStack()\n")
	g.Fprint(w, "    {\n")
}

// genInitialize generates the declaration and the call to the async Initialize method, and also fills stack
// outputs from the initialization result.
func (g *generator) genInitialize(w io.Writer, nodes []hcl2.Node) {
	g.Indented(func() {
		g.Fgenf(w, "%sOutput dict = Output.Create(Initialize());\n", g.Indent)
		for _, n := range nodes {
			switch n := n.(type) {
			case *hcl2.OutputVariable:
				// TODO migrate to java
				g.Fprintf(w, "%sthis.%s = dict.Apply(dict => dict[\"%s\"]);\n", g.Indent,
					propertyName(n.Name()), makeValidIdentifier(n.Name()))
			}
		}
	})
	g.Fgenf(w, "%s}\n\n", g.Indent)
	g.Fgenf(w, "%sprivate CompletableFuture<Map<String, Output<String>>> Initialize()\n", g.Indent)
	g.Fgenf(w, "%s{\n", g.Indent)
}

// genPostamble closes the method and the class and declares stack output statements.
func (g *generator) genPostamble(w io.Writer, nodes []hcl2.Node) {
	g.Indented(func() {
		// Return outputs from Initialize if needed
		if g.futureInit {
			// Emit stack output dictionary
			g.Indented(func() {
				g.Fprintf(w, "\n%sMap<String, Output<string>> outputs = new HashMap<String, Output<string>>();\n", g.Indent)
				g.Indented(func() {
					for _, n := range nodes {
						switch n := n.(type) {
						case *hcl2.OutputVariable:
							g.Fgenf(w, "%soutputs.put(%s, %[2]s);\n", g.Indent, n.Name())
						}
					}
				})
				g.Fprintf(w, "%sreturn outputs;\n", g.Indent)
			})
		}

		// Close class constructor or Initialize
		g.Fprintf(w, "%s}\n\n", g.Indent)

		// Emit stack output properties
		for _, n := range nodes {
			switch n := n.(type) {
			case *hcl2.OutputVariable:
				g.genOutputProperty(w, n)
			}
		}
	})
	g.Fprint(w, "}\n")
}

func (g *generator) genNode(w io.Writer, n hcl2.Node) {
	switch n := n.(type) {
	case *hcl2.Resource:
		g.genResource(w, n)
	case *hcl2.ConfigVariable:
		g.genConfigVariable(w, n)
	case *hcl2.LocalVariable:
		g.genLocalVariable(w, n)
	case *hcl2.OutputVariable:
		g.genOutputAssignment(w, n)
	}
}

// requiresAsyncInit returns true if the program requires awaits in the code, and therefore an asynchronous
// method must be declared.
func requiresAsyncInit(r *hcl2.Resource) bool {
	if r.Options == nil || r.Options.Range == nil {
		return false
	}

	return model.ContainsPromises(r.Options.Range.Type())
}

// resourceTypeName computes the Java class name for the given resource.
func (g *generator) resourceTypeName(r *hcl2.Resource) string {
	// Compute the resource type from the Pulumi type token.
	pkg, module, member, diags := r.DecomposeToken()
	contract.Assert(len(diags) == 0)
	if pkg == pulumiPackage && module == "providers" {
		pkg, module, member = member, "", "Provider"
	}

	packages := g.packages[pkg]
	rootPackage := packageName(packages, pkg)
	pkgName := packageName(packages, module)

	if pkgName != "" {
		pkgName = "." + pkgName
	}

	qualifiedMemberName := fmt.Sprintf("%s%s.%s", rootPackage, pkgName, Title(member))
	return qualifiedMemberName
}

func (g *generator) resourceArgsTypeName(r *hcl2.Resource) string {
	// Compute the resource type from the Pulumi type token.
	pkg, module, member, diags := r.DecomposeToken()
	contract.Assert(len(diags) == 0)
	if pkg == pulumiPackage && module == "providers" {
		pkg, module, member = member, "", "Provider"
	}

	namespaces := g.packages[pkg]
	rootNamespace := packageName(namespaces, pkg)
	namespace := packageName(namespaces, module)
	if g.compatibilities[pkg] == "kubernetes20" {
		namespace = fmt.Sprintf("types.inputs.%s", namespace)
	}

	if namespace != "" {
		namespace = "." + namespace
	}

	return fmt.Sprintf("%s%s.%sArgs", rootNamespace, namespace, Title(member))
}

// functionName computes the Java package and class name for the given function token.
func (g *generator) functionName(tokenArg model.Expression) (string, string) {
	token := tokenArg.(*model.TemplateExpression).Parts[0].(*model.LiteralValueExpression).Value.AsString()
	tokenRange := tokenArg.SyntaxNode().Range()

	// Compute the resource type from the Pulumi type token.
	pkg, module, member, diags := hcl2.DecomposeToken(token, tokenRange)
	contract.Assert(len(diags) == 0)
	packages := g.packages[pkg]
	rootPackage := packageName(packages, pkg)
	pkgName := packageName(packages, module)

	if pkgName != "" {
		pkgName = "." + pkgName
	}

	return rootPackage, fmt.Sprintf("%s%s.%s", rootPackage, pkgName, Title(member))
}

// argumentTypeName computes the Java argument class name for the given expression and model type.
func (g *generator) argumentTypeName(expr model.Expression, destType model.Type) string {
	schemaType, ok := hcl2.GetSchemaForType(destType.(model.Type))
	if !ok {
		return ""
	}

	objType, ok := schemaType.(*schema.ObjectType)
	if !ok {
		return ""
	}

	token := objType.Token
	tokenRange := expr.SyntaxNode().Range()
	qualifier := "inputs"
	if f, ok := g.functionArgs[token]; ok {
		token = f
		qualifier = ""
	}

	pkg, _, member, diags := hcl2.DecomposeToken(token, tokenRange)
	contract.Assert(len(diags) == 0)
	module := g.tokenToModules[pkg](token)
	packages := g.packages[pkg]
	rootPackage := packageName(packages, pkg)
	pkgName := packageName(packages, module)
	if strings.ToLower(pkgName) == "index" {
		pkgName = ""
	}
	if pkgName != "" {
		pkgName = "." + pkgName
	}
	if g.compatibilities[pkg] == "kubernetes20" {
		pkgName = ".types.inputs" + pkgName
	} else if qualifier != "" {
		pkgName = pkgName + "." + qualifier
	}
	member = member + "Args"

	return fmt.Sprintf("%s%s.%s", rootPackage, pkgName, Title(member))
}

// makeResourceName returns the expression that should be emitted for a resource's "name" parameter given its base name
// and the count variable name, if any.
func (g *generator) makeResourceName(baseName, count string) string {
	if count == "" {
		return fmt.Sprintf(`"%s"`, baseName)
	}
	return fmt.Sprintf("$\"%s-{%s}\"", baseName, count)
}

func (g *generator) genResourceOptions(opts *hcl2.ResourceOptions) string {
	if opts == nil {
		return ""
	}

	var result bytes.Buffer
	appendOption := func(value model.Expression) {
		if result.Len() == 0 {
			_, err := fmt.Fprintf(&result, ", new CustomResourceOptions%s(", g.Indent)
			g.Indent += "    "
			contract.IgnoreError(err)
		}

		g.Fgenf(&result, "\n%s%v,", g.Indent, g.lowerExpression(value, value.Type()))
	}

	if opts.Parent != nil {
		appendOption(opts.Parent)
	}
	if opts.Provider != nil {
		appendOption(opts.Provider)
	}
	if opts.DependsOn != nil {
		appendOption(opts.DependsOn)
	}
	if opts.Protect != nil {
		appendOption(opts.Protect)
	}
	if opts.IgnoreChanges != nil {
		appendOption(opts.IgnoreChanges)
	}

	if result.Len() != 0 {
		g.Indent = g.Indent[:len(g.Indent)-4]
		_, err := fmt.Fprintf(&result, "\n%s)", g.Indent)
		contract.IgnoreError(err)
	}

	return result.String()
}

// genResource handles the generation of instantiations of non-builtin resources.
func (g *generator) genResource(w io.Writer, r *hcl2.Resource) {
	qualifiedMemberName := g.resourceTypeName(r)
	argsName := g.resourceArgsTypeName(r)

	// Add conversions to input properties
	for _, input := range r.Inputs {
		destType, diagnostics := r.InputType.Traverse(hcl.TraverseAttr{Name: input.Name})
		g.diagnostics = append(g.diagnostics, diagnostics...)
		input.Value = g.lowerExpression(input.Value, destType.(model.Type))
	}

	name := r.Name()
	variableName := makeValidIdentifier(name)

	g.genTrivia(w, r.Definition.Tokens.GetType(""))
	for _, l := range r.Definition.Tokens.GetLabels(nil) {
		g.genTrivia(w, l)
	}

	instantiate := func(resName string) {
		g.Fgenf(w, "new %s(%s, new %s(", qualifiedMemberName, resName, argsName)
		g.Indented(func() {
			for _, attr := range r.Inputs {
				g.Fgenf(w, "\n%s", g.Indent)
				g.Fgenf(w, " %.v\n", attr.Value)
			}
		})

		if len(r.Inputs) <= 0 {
			g.Fgenf(w, ")%s)", g.genResourceOptions(r.Options))
		} else {
			g.Fgenf(w, "%s)%s)", g.Indent, g.genResourceOptions(r.Options))
		}
	}

	// TODO java ranges
	if r.Options != nil && r.Options.Range != nil {
		rangeType := model.ResolveOutputs(r.Options.Range.Type())
		rangeExpr := g.lowerExpression(r.Options.Range, rangeType)

		g.Fgenf(w, "%sList<%s> %s = new ArrayList<%s>();\n", qualifiedMemberName, g.Indent, variableName, qualifiedMemberName)

		resKey := "Key"
		if model.InputType(model.NumberType).ConversionFrom(rangeExpr.Type()) != model.NoConversion {
			g.Fgenf(w, "%sfor (int rangeIndex = 0; rangeIndex < %.12o; rangeIndex++)\n", g.Indent, rangeExpr)
			g.Fgenf(w, "%s{\n", g.Indent)
			g.Fgenf(w, "%s    var range = new { Value = rangeIndex };\n", g.Indent)
			resKey = "Value"
		} else {
			rangeExpr := &model.FunctionCallExpression{
				Name: "entries",
				Args: []model.Expression{rangeExpr},
			}
			g.Fgenf(w, "%sforeach (var range in %.v)\n", g.Indent, rangeExpr)
			g.Fgenf(w, "%s{\n", g.Indent)
		}

		resName := g.makeResourceName(name, "range."+resKey)
		g.Indented(func() {
			g.Fgenf(w, "%s%s.Add(", g.Indent, variableName)
			instantiate(resName)
			g.Fgenf(w, ");\n")
		})
		g.Fgenf(w, "%s}\n", g.Indent)
	} else {
		g.Fgenf(w, "%svar %s = ", g.Indent, variableName)
		instantiate(g.makeResourceName(name, ""))
		g.Fgenf(w, ";\n")
	}
}

func (g *generator) genConfigVariable(w io.Writer, v *hcl2.ConfigVariable) {
	if !g.configCreated {
		g.Fprintf(w, "%sConfig config = new Config();\n", g.Indent)
		g.configCreated = true
	}

	getType := "Object<dynamic>"
	switch v.Type() {
	case model.StringType:
		getType = "String"
	case model.NumberType, model.IntType:
		getType = "Int"
	case model.BoolType:
		getType = "Boolean"
	}

	getOrRequire := "Get"
	if v.DefaultValue == nil {
		getOrRequire = "Require"
	}

	if v.DefaultValue != nil {
		typ := v.DefaultValue.Type()
		if _, ok := typ.(*model.PromiseType); ok {
			g.Fgenf(w, "%[1]s%s %[2]s = Output.Create(config.%[3]s%[4]s(\"%[2]s\"))", g.Indent, v.Type(), v.Name(), getOrRequire, getType)
		} else {
			g.Fgenf(w, "%[1]s%s %[2]s = config.%[3]s%[4]s(\"%[2]s\")", g.Indent, v.Type(), v.Name(), getOrRequire, getType)
		}
		expr := g.lowerExpression(v.DefaultValue, v.DefaultValue.Type())
		g.Fgenf(w, " ?? %.v", expr)
	} else {
		g.Fgenf(w, "%[1]s%s %[2]s = config.%[3]s%[4]s(\"%[2]s\")", g.Indent, v.Type(), v.Name(), getOrRequire, getType)
	}
	g.Fgenf(w, ";\n")
}

func (g *generator) genLocalVariable(w io.Writer, v *hcl2.LocalVariable) {
	// TODO(pdg): trivia
	expr := g.lowerExpression(v.Definition.Value, v.Type())
	g.Fgenf(w, "%svar %s = %.3v;\n", g.Indent, makeValidIdentifier(v.Name()), expr)
}

func (g *generator) genOutputAssignment(w io.Writer, v *hcl2.OutputVariable) {
	if g.futureInit {
		g.Fgenf(w, "%s %s %s", g.Indent, v.Name(), makeValidIdentifier(v.Name()))
	} else {
		g.Fgenf(w, "%sthis.%s", g.Indent, propertyName(v.Name()))
	}
	g.Fgenf(w, " = %.3v;\n", g.lowerExpression(v.Value, v.Type()))
}

func (g *generator) genOutputProperty(w io.Writer, v *hcl2.OutputVariable) {
	// TODO(pdg): trivia
	// TODO: use annotation instead of getters and setters. Also it will be a string?
	//g.Fgenf(w, "%s@Output(\"%s\")\n", g.Indent, v.Name())
	g.Fgenf(w, "%sprivate %s %s;\n", g.Indent, v.Type(), v.Name())
	// TODO(msh): derive the element type of the Output from the type of its value.
	g.Fgenf(w, "%spublic void set%s(%s s) { this.%s = s; }\n", g.Indent, Title(propertyName(v.Name())), v.Type(), propertyName(v.Name()))
	g.Fgenf(w, "%spublic Output<%s> get%s() { return this.%s; }\n", g.Indent, v.Type(), Title(propertyName(v.Name())), propertyName(v.Name()))
}

func (g *generator) genNYI(w io.Writer, reason string, vs ...interface{}) {
	message := fmt.Sprintf("not yet implemented: %s", fmt.Sprintf(reason, vs...))
	g.diagnostics = append(g.diagnostics, &hcl.Diagnostic{
		Severity: hcl.DiagError,
		Summary:  message,
		Detail:   message,
	})
	g.Fgenf(w, "\"TODO: %s\"", fmt.Sprintf(reason, vs...))
}
