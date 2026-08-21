package com.raulsousa.pulso

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PulsoApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Sobe já conectado (ou já em modo demonstração) para que a primeira
        // tela nunca seja um vazio esperando configuração.
        container.applicationScope.launch {
            val settings = container.settingsRepository.settings.first()
            container.apply(settings)
        }
    }
}
