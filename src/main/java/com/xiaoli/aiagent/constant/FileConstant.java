package com.xiaoli.aiagent.constant;

/** 文件常量 */
public interface FileConstant {

    /** 默认文件保存目录，相对于应用启动目录；部署时可通过 app.storage.base-dir 覆盖。 */
    String FILE_SAVE_DIR = "./tmp";

    /** 默认聊天记录和模型记忆根目录，数据库中的文件路径继续相对于该目录保存。 */
    String CHAT_MEMORY_DIR = FILE_SAVE_DIR + "/chat-memory";
}
