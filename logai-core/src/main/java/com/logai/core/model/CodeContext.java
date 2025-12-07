package com.logai.core.model;

import java.util.List;

/**
 * Represents extracted code context around an error location.
 */
public class CodeContext {

    private final String filePath;
    private final int targetLine;
    private final String methodName;
    private final String className;
    private final List<String> surroundingLines;
    private final int startLine;
    private final int endLine;
    private final String methodBody;
    private final List<String> imports;
    private final List<String> classFields;

    private CodeContext(Builder builder) {
        this.filePath = builder.filePath;
        this.targetLine = builder.targetLine;
        this.methodName = builder.methodName;
        this.className = builder.className;
        this.surroundingLines = builder.surroundingLines;
        this.startLine = builder.startLine;
        this.endLine = builder.endLine;
        this.methodBody = builder.methodBody;
        this.imports = builder.imports;
        this.classFields = builder.classFields;
    }

    public String getFilePath() {
        return filePath;
    }

    public int getTargetLine() {
        return targetLine;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getClassName() {
        return className;
    }

    public List<String> getSurroundingLines() {
        return surroundingLines;
    }

    public int getStartLine() {
        return startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public String getMethodBody() {
        return methodBody;
    }

    public List<String> getImports() {
        return imports;
    }

    public List<String> getClassFields() {
        return classFields;
    }

    /**
     * Get the surrounding lines as a single formatted string with line numbers.
     */
    public String getFormattedContext() {
        if (surroundingLines == null || surroundingLines.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int lineNum = startLine;
        for (String line : surroundingLines) {
            String marker = (lineNum == targetLine) ? " >>> " : "     ";
            sb.append(String.format("%s%4d | %s%n", marker, lineNum, line));
            lineNum++;
        }
        return sb.toString();
    }

    /**
     * Get context as plain code (without line numbers).
     */
    public String getPlainContext() {
        if (surroundingLines == null) {
            return "";
        }
        return String.join("\n", surroundingLines);
    }

    @Override
    public String toString() {
        return "CodeContext{" +
                "filePath='" + filePath + '\'' +
                ", targetLine=" + targetLine +
                ", methodName='" + methodName + '\'' +
                ", className='" + className + '\'' +
                ", lines=" + startLine + "-" + endLine +
                '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String filePath;
        private int targetLine;
        private String methodName;
        private String className;
        private List<String> surroundingLines;
        private int startLine;
        private int endLine;
        private String methodBody;
        private List<String> imports;
        private List<String> classFields;

        public Builder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }

        public Builder targetLine(int targetLine) {
            this.targetLine = targetLine;
            return this;
        }

        public Builder methodName(String methodName) {
            this.methodName = methodName;
            return this;
        }

        public Builder className(String className) {
            this.className = className;
            return this;
        }

        public Builder surroundingLines(List<String> surroundingLines) {
            this.surroundingLines = surroundingLines;
            return this;
        }

        public Builder startLine(int startLine) {
            this.startLine = startLine;
            return this;
        }

        public Builder endLine(int endLine) {
            this.endLine = endLine;
            return this;
        }

        public Builder methodBody(String methodBody) {
            this.methodBody = methodBody;
            return this;
        }

        public Builder imports(List<String> imports) {
            this.imports = imports;
            return this;
        }

        public Builder classFields(List<String> classFields) {
            this.classFields = classFields;
            return this;
        }

        public CodeContext build() {
            return new CodeContext(this);
        }
    }
}

