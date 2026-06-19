#!/usr/bin/env bash
# Use lark-cli with content from a file variable
CONTENT=$(cat test-doc.md)
lark-cli.cmd docs +create --title "miniclaw 测试文档" --markdown "$CONTENT"
