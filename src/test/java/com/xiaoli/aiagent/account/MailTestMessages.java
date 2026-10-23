package com.xiaoli.aiagent.account;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;

/** 测试中按 MIME 类型读取邮件正文，兼容纯 HTML 和包含内嵌图片的邮件。 */
final class MailTestMessages {
    private MailTestMessages() {}

    /** 递归查找正文或图片，不依赖各邮件部件的排列顺序。 */
    static Part find(Part message, String contentType) throws Exception {
        if (message.isMimeType(contentType)) return message;
        if (message.isMimeType("multipart/*")) {
            Multipart parts = (Multipart) message.getContent();
            for (int index = 0; index < parts.getCount(); index++) {
                Part result = find(parts.getBodyPart(index), contentType);
                if (result != null) return result;
            }
        }
        return null;
    }

    /** 提取验证码所在的 HTML 正文，缺失时直接说明测试失败原因。 */
    static String html(Part message) throws Exception {
        if (message instanceof MimeMessage mime) mime.saveChanges();
        Part html = find(message, "text/html");
        if (html == null) throw new AssertionError("邮件缺少 HTML 正文");
        return (String) html.getContent();
    }
}
