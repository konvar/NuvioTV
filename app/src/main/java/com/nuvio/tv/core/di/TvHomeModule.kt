package com.nuvio.tv.core.di

import com.nuvio.tv.tvhome.TvHomePublisher
import com.nuvio.tv.tvhome.TvHomePublisherImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TvHomeModule {

    @Binds
    @Singleton
    abstract fun bindTvHomePublisher(impl: TvHomePublisherImpl): TvHomePublisher
}
