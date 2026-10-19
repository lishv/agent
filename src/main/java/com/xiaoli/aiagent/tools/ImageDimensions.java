package com.xiaoli.aiagent.tools;

/** 从实际图片文件头读取的像素尺寸，不使用搜索页标签或缩略图尺寸。 */
public record ImageDimensions(int width, int height) {}
