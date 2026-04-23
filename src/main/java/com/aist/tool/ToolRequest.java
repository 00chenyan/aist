package com.aist.tool;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Tool invocation request.
 */
@Data
public class ToolRequest {

    /**
     * Tool name.
     */
    private String toolName;

    /**
     * Argument list.
     */
    private List<String> arguments = new ArrayList<>();

    public ToolRequest() {
    }

    public ToolRequest(String toolName, List<String> arguments) {
        this.toolName = toolName;
        this.arguments = arguments != null ? arguments : new ArrayList<>();
    }

    /**
     * Returns arguments as a single string (for logging).
     *
     * @return joined arguments
     */
    public String getArgumentsString() {
        return String.join(", ", arguments);
    }

    /**
     * Returns the first argument.
     *
     * @return first argument, or empty string if none
     */
    public String getFirstArgument() {
        return arguments.isEmpty() ? "" : arguments.get(0);
    }

    /**
     * Whether any arguments were provided.
     *
     * @return true if non-empty
     */
    public boolean hasArguments() {
        return !arguments.isEmpty();
    }
}
