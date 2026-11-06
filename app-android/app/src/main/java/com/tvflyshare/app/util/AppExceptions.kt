package com.tvflyshare.app.util

import com.tvflyshare.app.R

/**
 * 应用程序的统一异常基类。
 * 所有业务相关的异常都应该继承自此类，以便于统一处理和用户提示。
 * @param uiMessage 用户友好的错误信息，可以直接显示给用户。
 * @param cause 原始的异常链。
 */
sealed class AppException(val uiMessage: UiText, cause: Throwable? = null) : Exception(cause) {

    /** 网络相关的异常 */
    class NetworkException(cause: Throwable? = null, specificMessage: UiText? = null) :
        AppException(specificMessage ?: UiText.StringResource(R.string.error_network_generic), cause)

    /** 存储相关的异常，如读写权限、文件不存在等 */
    class StorageException(cause: Throwable? = null, specificMessage: UiText? = null) :
        AppException(specificMessage ?: UiText.StringResource(R.string.error_storage_generic), cause)

    /** SMB 连接或操作相关的异常 */
    class SmbException(cause: Throwable? = null, specificMessage: UiText? = null) :
        AppException(specificMessage ?: UiText.StringResource(R.string.error_smb_generic), cause)

    /** 操作被取消 */
    class CancellationException(cause: Throwable? = null) :
        AppException(UiText.StringResource(R.string.error_operation_cancelled), cause)

    /** 未知或未处理的异常 */
    class UnknownException(cause: Throwable? = null, specificMessage: UiText? = null) :
        AppException(specificMessage ?: UiText.StringResource(R.string.error_unknown_generic), cause)

    companion object {
        /**
         * 将任何 Throwable 转换为 AppException，便于统一处理。
         */
        fun fromThrowable(throwable: Throwable): AppException {
            return when (throwable) {
                is AppException -> throwable
                is java.net.SocketException, 
                is java.net.UnknownHostException, 
                is java.net.ConnectException,
                is java.net.SocketTimeoutException,
                is javax.net.ssl.SSLException -> NetworkException(throwable)
                is java.io.IOException -> StorageException(throwable, UiText.StringResource(R.string.error_io_generic))
                is SecurityException -> StorageException(throwable, UiText.StringResource(R.string.error_permission_denied))
                is kotlinx.coroutines.CancellationException -> CancellationException(throwable)
                else -> UnknownException(throwable, UiText.DynamicString(throwable.localizedMessage ?: "未知错误"))
            }
        }
    }
}
