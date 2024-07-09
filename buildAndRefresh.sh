./gradlew build
rm run/lambda/plugins/ThunderPacketFly*.jar
rm run/lambda/plugins/ThunderPacketFly*.jar.disabled
cp build/libs/ThunderPacketFly*.jar run/lambda/plugins