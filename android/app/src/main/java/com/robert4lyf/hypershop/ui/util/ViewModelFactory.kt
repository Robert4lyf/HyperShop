package com.robert4lyf.hypershop.ui.util

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/** A one-off factory for a ViewModel built by hand from the AppContainer (BangGym's helper). */
inline fun <reified VM : ViewModel> factoryFor(crossinline create: (CreationExtras) -> VM): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { create(this) }
    }
