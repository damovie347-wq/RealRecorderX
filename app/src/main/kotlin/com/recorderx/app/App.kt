package com.recorderx.app

import android.app.Application

/**
 * Intentionally close to empty. There's no DI framework, analytics SDK, or
 * crash reporter to bootstrap here -- every one of those is a dependency
 * (and a slice of cold-start time) this project doesn't need.
 */
class App : Application()
