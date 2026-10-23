package com.xiaoli.aiagent.tools;

/** 从当前图片文件头读取的实际像素尺寸；缩略图同样按其真实尺寸记录。 */
public record ImageDimensions(int width, int height) {}
