package org.example.goshop.common.exception;

import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 上传大小异常识别工具，兼容 Spring 与嵌入式 Tomcat 抛出的不同异常类型。 */
final class UploadSizeExceptionSupport {

    private static final String TOMCAT_FILE_SIZE_EXCEPTION =
            "org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException";
    private static final String TOMCAT_REQUEST_SIZE_EXCEPTION =
            "org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException";

    private UploadSizeExceptionSupport() {
    }

    static boolean isExceeded(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof MaxUploadSizeExceededException) {
                return true;
            }

            String exceptionName = current.getClass().getName();
            if (TOMCAT_FILE_SIZE_EXCEPTION.equals(exceptionName)
                    || TOMCAT_REQUEST_SIZE_EXCEPTION.equals(exceptionName)) {
                return true;
            }
        }
        return false;
    }
}
