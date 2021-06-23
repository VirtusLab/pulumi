module github.com/pulumi/pulumi/pkg/v3/codegen/jvm

go 1.16

replace (
	github.com/pulumi/pulumi/pkg/v3 => ../..
	github.com/pulumi/pulumi/sdk/v3 => ../../../sdk
)

require (
	github.com/pkg/errors v0.9.1
	github.com/pulumi/pulumi/pkg/v3 v3.5.1
	github.com/pulumi/pulumi/sdk/v3 v3.5.1
	github.com/stretchr/testify v1.7.0
)
