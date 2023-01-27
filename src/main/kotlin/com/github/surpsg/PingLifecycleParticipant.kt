package com.github.surpsg

import org.apache.maven.AbstractMavenLifecycleParticipant
import org.apache.maven.MavenExecutionException
import org.apache.maven.execution.MavenSession
import org.codehaus.plexus.component.annotations.Component


@Component(role = AbstractMavenLifecycleParticipant::class, hint = "org.mng.example.PingLifecycleParticipant")
class PingLifecycleParticipant : AbstractMavenLifecycleParticipant() {
    @Throws(MavenExecutionException::class)
    override fun afterProjectsRead(session: MavenSession) {
        println("afterProjectsRead OK")
    }

    @Throws(MavenExecutionException::class)
    override fun afterSessionEnd(session: MavenSession) {
        println("afterSessionEnd OK")
    }
}
