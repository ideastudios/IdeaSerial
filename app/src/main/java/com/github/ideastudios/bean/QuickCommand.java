package com.github.ideastudios.bean;

/**
 * 快捷发送命令
 */
public class QuickCommand {
    public String name = "";
    public String content = "";
    public boolean isHex = false;
    public boolean appendNewline = false;

    public QuickCommand() {
    }

    public QuickCommand(String name, String content, boolean isHex, boolean appendNewline) {
        this.name = name;
        this.content = content;
        this.isHex = isHex;
        this.appendNewline = appendNewline;
    }
}
