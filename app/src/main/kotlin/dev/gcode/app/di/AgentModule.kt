package dev.gcode.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gcode.app.agent.DefaultExtraTools
import dev.gcode.app.agent.ExtraToolsProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentModule {
    @Binds
    abstract fun extraTools(impl: DefaultExtraTools): ExtraToolsProvider
}
