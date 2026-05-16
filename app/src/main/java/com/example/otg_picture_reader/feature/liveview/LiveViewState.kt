package com.example.otg_picture_reader.feature.liveview

/**
 * Live View 功能的 UI 状态。
 * 在 [LiveViewViewModel] 中管理，由 [LiveViewScreen] 消费。
 */
data class LiveViewState(
    /** Live View 是否正在运行 */
    val isActive: Boolean = false,
    /** 是否正在连接相机 */
    val isConnecting: Boolean = false,
    /** 当前帧 JPEG 数据 */
    val currentFrame: ByteArray? = null,
    /** 累计接收帧数 */
    val frameCount: Long = 0,
    /** 瞬时帧率 (fps) */
    val fps: Float = 0f,
    /** 当前相机参数 */
    val params: LiveViewParams? = null,
    /** 当前是否正在向相机发送对焦/拍摄指令 */
    val isCommandBusy: Boolean = false,
    /** 错误信息 */
    val errorMessage: String? = null
) {
    /** 当前帧宽高（从 JPEG 解码获得，初始为空） */
    val frameWidth: Int? = null
    val frameHeight: Int? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LiveViewState) return false
        return isActive == other.isActive &&
            isConnecting == other.isConnecting &&
            frameCount == other.frameCount &&
            fps == other.fps &&
            params == other.params &&
            isCommandBusy == other.isCommandBusy &&
            errorMessage == other.errorMessage
    }

    override fun hashCode(): Int {
        var result = isActive.hashCode()
        result = 31 * result + isConnecting.hashCode()
        result = 31 * result + frameCount.hashCode()
        result = 31 * result + fps.hashCode()
        result = 31 * result + (params?.hashCode() ?: 0)
        result = 31 * result + isCommandBusy.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        return result
    }
}

/** 参数显示用的格式化数据 */
data class FormattedParams(
    val shutterSpeed: String = "--",
    val aperture: String = "--",
    val iso: String = "--",
    val exposureComp: String = "--",
    val shootingMode: String = "--",
    val whiteBalance: String = "--",
    val battery: String = "--",
    val remainingShots: String = "--"
)
