package util
import net.minecraft.util.math.Vec3d
import net.minecraft.util.math.Vec3i

class TimeVec3d : Vec3d {
    val time: Long

    constructor(xIn: Double, yIn: Double, zIn: Double, time: Long) : super(xIn, yIn, zIn) {
        this.time = time
    }

    constructor(vector: Vec3i?, time: Long) : super(vector) {
        this.time = time
    }
}