package idk.bluecross.tpf.util

import net.minecraftforge.fml.common.eventhandler.Event

class PushEvent:Event(){
    override fun isCancelable(): Boolean = true
}