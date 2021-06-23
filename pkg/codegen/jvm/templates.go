// Copyright 2016-2020, Pulumi Corporation.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// nolint: lll
package jvm

import (
	"strings"
	"text/template"

	"github.com/pulumi/pulumi/pkg/v3/codegen/schema"
)

// TODO(pdg): parameterize package name
const javaSettingsFileText = `pluginManagement {
  repositories {
      maven { // The google mirror is less flaky than mavenCentral()
          url("https://maven-central.storage-download.googleapis.com/maven2/")
      }
      gradlePluginPortal()
  }
}

rootProject.name = "{{ .Package.Name }}"
include("{{ .Package.Name }}")`

var javaSettingsFileTemplate = template.Must(template.New("JavaSettings").Funcs(template.FuncMap{
	// ispulumipkg is used in the template to conditionally emit `ExcludeAssets="contentFiles"`
	// for `<PackageReference>`s that start with "Pulumi.", to prevent the references's contentFiles
	// from being included in this project's package. Otherwise, if a reference has version.txt
	// in its contentFiles, and we don't exclude contentFiles for the reference, the reference's
	// version.txt will be used over this project's version.txt.
	"ispulumipkg": func(s string) bool {
		return strings.HasPrefix(s, "Pulumi.")
	},
}).Parse(javaSettingsFileText))

type javaSettingsFileTemplateContext struct {
	Package *schema.Package
}

const javaBuildFileText = `plugins {
  id("java-library")
}

repositories {
  maven { // The google mirror is less flaky than mavenCentral()
      url("https://maven-central.storage-download.googleapis.com/maven2/")
  }
  mavenCentral()
  mavenLocal()
}

dependencies {
  testImplementation("junit:junit:4.13.1")
}`

var javaBuildFileTemplate = template.Must(template.New("JavaBuild").Funcs(template.FuncMap{
	// ispulumipkg is used in the template to conditionally emit `ExcludeAssets="contentFiles"`
	// for `<PackageReference>`s that start with "Pulumi.", to prevent the references's contentFiles
	// from being included in this project's package. Otherwise, if a reference has version.txt
	// in its contentFiles, and we don't exclude contentFiles for the reference, the reference's
	// version.txt will be used over this project's version.txt.
	"ispulumipkg": func(s string) bool {
		return strings.HasPrefix(s, "Pulumi.")
	},
}).Parse(javaBuildFileText))

type javaBuildFileTemplateContext struct {
}
