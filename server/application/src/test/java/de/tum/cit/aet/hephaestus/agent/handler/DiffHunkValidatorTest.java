package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DiffHunkValidatorTest extends BaseUnitTest {

    @Nested
    class ParseValidLines {

        @Test
        void nullDiff() {
            assertThat(DiffHunkValidator.parseValidLines(null)).isEmpty();
        }

        @Test
        void blankDiff() {
            assertThat(DiffHunkValidator.parseValidLines("   ")).isEmpty();
        }

        @Test
        void singleFileDiff() {
            String diff = """
                diff --git a/src/Main.swift b/src/Main.swift
                --- a/src/Main.swift
                +++ b/src/Main.swift
                @@ -1,3 +1,5 @@
                 import Foundation
                +import UIKit
                +
                 class Main {
                +    let x = 1
                """;
            Map<String, TreeSet<Integer>> result = DiffHunkValidator.parseValidLines(diff);

            assertThat(result).containsKey("src/Main.swift");
            TreeSet<Integer> lines = result.get("src/Main.swift");
            assertThat(lines).contains(1, 2, 3, 4, 5);
        }

        @Test
        void multiFileDiff() {
            String diff = """
                diff --git a/FileA.swift b/FileA.swift
                --- a/FileA.swift
                +++ b/FileA.swift
                @@ -1,2 +1,3 @@
                 line1
                +added
                 line2
                diff --git a/FileB.swift b/FileB.swift
                --- a/FileB.swift
                +++ b/FileB.swift
                @@ -1,1 +1,2 @@
                 existing
                +new line
                """;
            Map<String, TreeSet<Integer>> result = DiffHunkValidator.parseValidLines(diff);

            assertThat(result).hasSize(2);
            assertThat(result.get("FileA.swift")).containsExactly(1, 2, 3);
            assertThat(result.get("FileB.swift")).containsExactly(1, 2);
        }

        @Test
        void deletedLines() {
            String diff = """
                diff --git a/File.swift b/File.swift
                --- a/File.swift
                +++ b/File.swift
                @@ -1,4 +1,3 @@
                 kept
                -removed1
                -removed2
                 also kept
                +added
                """;
            Map<String, TreeSet<Integer>> result = DiffHunkValidator.parseValidLines(diff);

            TreeSet<Integer> lines = result.get("File.swift");
            assertThat(lines).containsExactly(1, 2, 3);
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void shouldKeepSourceTextWhenItContainsAHunkHeader(boolean annotated) {
            String prefix = annotated ? "[L2] " : "";
            String nextPrefix = annotated ? "[L3] " : "";
            String diff = """
                diff --git a/example.txt b/example.txt
                --- a/example.txt
                +++ b/example.txt
                @@ -1,1 +1,3 @@
                 existing
                """ + prefix + "+Example: @@ -1,2 +90,3 @@\n" + nextPrefix + "+next line\n";

            assertThat(DiffHunkValidator.parseValidLines(diff).get("example.txt"))
                    .containsExactly(1, 2, 3);
        }

        @Test
        void multipleHunks() {
            String diff = """
                diff --git a/File.swift b/File.swift
                --- a/File.swift
                +++ b/File.swift
                @@ -1,2 +1,3 @@
                 line1
                +inserted
                 line2
                @@ -10,2 +11,3 @@
                 line10
                +another insert
                 line11
                """;
            Map<String, TreeSet<Integer>> result = DiffHunkValidator.parseValidLines(diff);

            TreeSet<Integer> lines = result.get("File.swift");
            assertThat(lines).contains(1, 2, 3, 11, 12, 13);
        }

        @Test
        void annotatedDiff() {
            String diff = """
                diff --git a/File.swift b/File.swift
                --- a/File.swift
                +++ b/File.swift
                @@ -1,2 +1,3 @@
                [L1]  import Foundation
                [L2] +import UIKit
                [L3]  class Main {
                """;
            Map<String, TreeSet<Integer>> result = DiffHunkValidator.parseValidLines(diff);

            TreeSet<Integer> lines = result.get("File.swift");
            assertThat(lines).containsExactly(1, 2, 3);
        }

        @Test
        void renameDiff() {
            String diff = """
                diff --git a/old/File.swift b/new/File.swift
                --- a/old/File.swift
                +++ b/new/File.swift
                @@ -1,1 +1,2 @@
                 existing
                +added
                """;
            Map<String, TreeSet<Integer>> result = DiffHunkValidator.parseValidLines(diff);

            assertThat(result).containsKey("new/File.swift");
            assertThat(result.get("new/File.swift")).containsExactly(1, 2);
        }

        @Test
        void newFile() {
            String diff = """
                diff --git a/NewFile.swift b/NewFile.swift
                --- /dev/null
                +++ b/NewFile.swift
                @@ -0,0 +1,3 @@
                +line1
                +line2
                +line3
                """;
            Map<String, TreeSet<Integer>> result = DiffHunkValidator.parseValidLines(diff);

            assertThat(result).containsKey("NewFile.swift");
            assertThat(result.get("NewFile.swift")).containsExactly(1, 2, 3);
        }

        @Test
        void binaryFile() {
            String diff = """
                diff --git a/image.png b/image.png
                Binary files differ
                """;
            Map<String, TreeSet<Integer>> result = DiffHunkValidator.parseValidLines(diff);

            // Binary file should have an entry but no valid lines
            assertThat(result.getOrDefault("image.png", new TreeSet<>())).isEmpty();
        }

        @Test
        @DisplayName("parses a space-containing (git-quoted) path from the +++ b/ line")
        void quotedPathFromPlusLine() {
            // git quotes paths containing spaces: `diff --git "a/x y" "b/x y"`. The `diff --git` heuristic
            // (lastIndexOf " b/") cannot find the path there, so currentFile must come from the +++ b/ line
            // (which is unquoted-stripped) — otherwise this file's notes never match and validation is disabled.
            String diff = """
                diff --git "a/src/my file.swift" "b/src/my file.swift"
                --- "a/src/my file.swift"
                +++ "b/src/my file.swift"
                @@ -1,1 +1,2 @@
                 existing
                +added
                """;
            Map<String, TreeSet<Integer>> result = DiffHunkValidator.parseValidLines(diff);

            assertThat(result).containsKey("src/my file.swift");
            assertThat(result.get("src/my file.swift")).containsExactly(1, 2);
        }

        @Test
        @DisplayName("handles hunk starting at line 0 (pure addition)")
        void hunkStartAtZero() {
            String diff = """
                diff --git a/File.swift b/File.swift
                --- /dev/null
                +++ b/File.swift
                @@ -0,0 +1,2 @@
                +first
                +second
                """;
            Map<String, TreeSet<Integer>> result = DiffHunkValidator.parseValidLines(diff);

            assertThat(result.get("File.swift")).containsExactly(1, 2);
        }
    }
}
