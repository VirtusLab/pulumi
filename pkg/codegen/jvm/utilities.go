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

package jvm

import (
	"regexp"
	"strings"
	"unicode"

	"github.com/pulumi/pulumi/pkg/v3/codegen"
)

// Title converts the input string to a title case
// where only the initial letter is upper-cased.
func Title(s string) string {
	if s == "" {
		return ""
	}
	runes := []rune(s)
	return string(append([]rune{unicode.ToUpper(runes[0])}, runes[1:]...))
}

// isReservedWord returns true if s is a java reserved word as per
// https://docs.oracle.com/javase/tutorial/java/nutsandbolts/_keywords.html
func isReservedWord(s string) bool {
	switch s {
	case "abstract", "continue", "for", "new", "switch",
		"assert", "default", "goto", "package", "synchronized",
		"boolean", "do", "if", "private", "this",
		"break", "double", "implements", "protected", "throw",
		"byte", "else", "import", "public", "throws",
		"case", "enum", "instanceof", "return", "transient",
		"catch", "extends", "int", "short", "try",
		"char", "final", "interface", "static", "void",
		"class", "finally", "long", "strictfp", "volatile",
		"const", "float", "native", "super", "while":
		return true
	// Treat contextual keywords as keywords, as we don't validate the context around them.
	case "non-sealed", "permits", "record", "sealed", "var", "yield":
		return true
	// Treat contextual keywords as keywords, as we don't validate the context around them.
	case "true", "false", "null":
		return true
	default:
		return false
	}
}

// // isLegalIdentifierStart returns true if it is legal for c to be the first character of a C# identifier as per
// // https://docs.microsoft.com/en-us/dotnet/csharp/language-reference/language-specification/lexical-structure
// func isLegalIdentifierStart(c rune) bool {
// 	return c == '_' || c == '@' ||
// 		unicode.In(c, unicode.Lu, unicode.Ll, unicode.Lt, unicode.Lm, unicode.Lo, unicode.Nl)
// }

// // isLegalIdentifierPart returns true if it is legal for c to be part of a C# identifier (besides the first character)
// // as per https://docs.microsoft.com/en-us/dotnet/csharp/language-reference/language-specification/lexical-structure
// func isLegalIdentifierPart(c rune) bool {
// 	return c == '_' ||
// 		unicode.In(c, unicode.Lu, unicode.Ll, unicode.Lt, unicode.Lm, unicode.Lo, unicode.Nl, unicode.Mn, unicode.Mc,
// 			unicode.Nd, unicode.Pc, unicode.Cf)
// }

// makeValidIdentifier replaces characters that are not allowed in java identifiers with underscores. A reserved word is
// prefixed with @. No attempt is made to ensure that the result is unique.
func makeValidIdentifier(name string) string {
	// var builder strings.Builder
	// for i, c := range name {
	// 	if i == 0 && c == '@' {
	// 		builder.WriteRune(c)
	// 		continue
	// 	}
	// 	if !isLegalIdentifierPart(c) {
	// 		builder.WriteRune('_')
	// 	} else {
	// 		if i == 0 && !isLegalIdentifierStart(c) {
	// 			builder.WriteRune('_')
	// 		}
	// 		builder.WriteRune(c)
	// 	}
	// }
	// name = builder.String()
	if isReservedWord(name) {
		return name + "$"
	}
	return name
}

func makeSafeEnumName(name, typeName string) (string, error) {
	// Replace common single character enum names.
	safeName := codegen.ExpandShortEnumName(name)

	// // If the name is one illegal character, return an error.
	// if len(safeName) == 1 && !isLegalIdentifierStart(rune(safeName[0])) {
	// 	return "", errors.Errorf("enum name %s is not a valid identifier", safeName)
	// }

	// Capitalize and make a valid identifier.
	safeName = strings.Title(makeValidIdentifier(safeName))

	// If there are multiple underscores in a row, replace with one.
	regex := regexp.MustCompile(`_+`)
	safeName = regex.ReplaceAllString(safeName, "_")

	// If the enum name starts with an underscore, add the type name as a prefix.
	if strings.HasPrefix(safeName, "_") {
		safeName = typeName + safeName
	}

	// "Equals" conflicts with a method on the EnumType struct, change it to EqualsValue.
	if safeName == "Equals" {
		safeName = "EqualsValue"
	}

	return safeName, nil
}
