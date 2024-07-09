package idk.bluecross.tpf

import com.lambda.client.plugin.api.Plugin

internal object ThunderPacketFLyPlugin : Plugin() {

    override fun onLoad() {
        modules.add(ThunderPacketFLy)
    }

    override fun onUnload() {
        modules.forEach {
            it.disable()
        }
    }
}