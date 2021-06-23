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
			//TODO: this is not final
			"Simple schema with enum types",
			"simple-enum-schema",
			[]string{
				"Plant/Provider.java",
				"Plant/Tree/V1/README.md",
				"Plant/Tree/V1/Diameter.java",
				"Plant/Inputs/ContainerArgs.java",
				"Plant/Outputs/Container.java",
				"Plant/ContainerBrightness.java",
				"Plant/ContainerColor.java",
				"Plant/Tree/V1/Nursery.java",
				"Plant/README.md",
				"Plant/ContainerSize.java",
				"Plant/tree/README.md",
				"Plant/Tree/V1/RubberTree.java",
				"Plant/Tree/V1/Farm.java",
				"Plant/Tree/V1/RubberTreeVariety.java",
				"Plant/Tree/V1/TreeSize.java",
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
