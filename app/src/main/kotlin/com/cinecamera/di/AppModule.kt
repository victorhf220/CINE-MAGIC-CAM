package com.cinecamera.di

import android.content.Context
import com.cinecamera.audio.IAudioEngine
import com.cinecamera.audio.ProfessionalAudioEngine
import com.cinecamera.camera.ICameraEngine
import com.cinecamera.encoding.IEncodingEngine
import com.cinecamera.imageprocessing.IImageProcessingEngine
import com.cinecamera.stabilization.IStabilizationEngine
import com.cinecamera.stability.SystemStabilityEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * AppModule
 *
 * Central Hilt dependency injection module.
 * Binds all engine interfaces to their singleton implementations.
 *
 * Architecture note: All engines are @Singleton because they hold
 * hardware resources (Camera2 sessions, MediaCodec instances, AudioRecord)
 * that must not be duplicated. The ViewModel requests them via interface
 * types, ensuring testability — tests inject mock implementations.
 *
 * UseCases are not declared here because Hilt auto-discovers them via
 * @Inject constructor. They are provided implicitly to the ViewModel.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineBindingsModule {

    @Binds
    @Singleton
    abstract fun bindAudioEngine(impl: ProfessionalAudioEngine): IAudioEngine

    @Binds
    @Singleton
    abstract fun bindCameraEngine(impl: CameraEngineImpl): ICameraEngine

    @Binds
    @Singleton
    abstract fun bindEncodingEngine(impl: EncodingEngineImpl): IEncodingEngine

    @Binds
    @Singleton
    abstract fun bindImageProcessingEngine(impl: ImageProcessingEngineImpl): IImageProcessingEngine

    @Binds
    @Singleton
    abstract fun bindStabilizationEngine(impl: StabilizationEngineImpl): IStabilizationEngine

    @Binds
    @Singleton
    abstract fun bindStabilityEngine(impl: SystemStabilityEngineImpl): SystemStabilityEngine
}

/**
 * ProvidersModule
 *
 * Provides objects that cannot be bound via @Binds (third-party classes,
 * objects with factory methods, or classes requiring @ApplicationContext).
 */
@Module
@InstallIn(SingletonComponent::class)
object ProvidersModule {

    /**
     * Provides the application Context for engines that need it
     * (AudioEngine, TelemetryEngine, RecoveryEngine, MonetizationEngine).
     * Hilt provides @ApplicationContext automatically — this explicit
     * provision ensures the correct Context type is used everywhere.
     */
    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context
}
