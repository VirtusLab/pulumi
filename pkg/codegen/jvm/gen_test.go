package jvm

import (
	"os"
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
				"settings.gradle",
			},
		},
		{
			"Simple schema with enum types",
			"simple-enum-schema",
			[]string{
				"settings.gradle",
			},
		},
		// TODO: need to add external package
		// {
		// 	"External resource schema",
		// 	"external-resource-schema",
		// 	[]string{
		// 		"settings.gradle",
		// 	},
		// },
		{
			"Simple schema with plain properties",
			"simple-plain-schema",
			[]string{
				"settings.gradle",
			},
		},
	}
	testDir := filepath.Join("..", "internal", "test", "testdata")
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			files, err := test.GeneratePackageFilesFromSchema(
				filepath.Join(testDir, tt.schemaDir, "schema.json"), GeneratePackage)
			assert.NoError(t, err)

			for path, file := range files {
				fullPath := filepath.Join(testDir, tt.schemaDir, "jvm", path)
				dir := filepath.Dir(fullPath)
				os.MkdirAll(dir, 0777)
				out, err := os.Create(fullPath)
				if err == nil {
					out.Write(file)
				}
				out.Close()
			}

			dir := filepath.Join(testDir, tt.schemaDir)
			lang := "jvm"

			test.RewriteFilesWhenPulumiAccept(t, dir, lang, files)

			expectedFiles, err := test.LoadFiles(filepath.Join(testDir, tt.schemaDir), lang, tt.expectedFiles)
			assert.NoError(t, err)

			test.ValidateFileEquality(t, files, expectedFiles)
		})
	}
}
