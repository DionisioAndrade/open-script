package com.example.open_script.media

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** A compatible retry uses platform software decoders without altering device security settings. */
@UnstableApi
internal object SoftwareDecoders {
    val selector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            .filter { it.softwareOnly && !it.hardwareAccelerated }
    }
}
