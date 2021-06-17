package jvm

import (
	"path/filepath"
	"testing"

	"github.com/pulumi/pulumi/pkg/v3/codegen/internal/test"
	"github.com/stretchr/testify/assert"
)

func TestGeneratePackage(t *testing.T) {
	tests := []struct {
		name          string
		schemaDir     string
		expectedFiles []string
	}{
		{
			"Simple schema with local resource properties",
			"simple-resource-schema",
			[]string{
				"Resource.java",
				"OtherResource.java",
				"ArgFunction.java",
			},
		},
		{
			"Simple schema with enum types",
			"simple-enum-schema",
			[]string{
				"tree/V1/RubberTree.java",
				"tree/V1/Nursery.java",
				"tree/V1/Enums.java",
				"Enums.java",
				"inputs/ContainerArgs.java",
				"outputs/Container.java",
			},
		},
		{
			"External resource schema",
			"external-resource-schema",
			[]string{
				"inputs/PetArgs.java",
				"ArgFunction.java",
				"Cat.java",
				"Component.java",
				"Workload.java",
			},
		},
		{
			"Simple schema with plain properties",
			"simple-plain-schema",
			[]string{
				"Inputs/FooArgs.cs",
				"Component.cs",
			},
		},
	}
	testDir := filepath.Join("..", "internal", "test", "testdata")
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			files, err := test.GeneratePackageFilesFromSchema(
				filepath.Join(testDir, tt.schemaDir, "schema.json"), GeneratePackage)
			assert.NoError(t, err)

			dir := filepath.Join(testDir, tt.schemaDir)
			lang := "jvm"

			test.RewriteFilesWhenPulumiAccept(t, dir, lang, files)

			expectedFiles, err := test.LoadFiles(filepath.Join(testDir, tt.schemaDir), lang, tt.expectedFiles)
			assert.NoError(t, err)

			test.ValidateFileEquality(t, files, expectedFiles)
		})
	}
}

