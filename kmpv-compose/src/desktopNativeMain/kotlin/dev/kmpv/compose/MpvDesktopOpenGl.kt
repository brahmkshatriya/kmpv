@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpv.compose

import dev.kmpv.MpvOpenGlNativeDisplay
import kotlinx.cinterop.COpaquePointer
import sdl3.SDL_GL_GetProcAddress

internal fun sdlOpenGlProcAddress(name: String): COpaquePointer? = SDL_GL_GetProcAddress(name)

internal fun platformOpenGlNativeDisplay(): MpvOpenGlNativeDisplay = MpvOpenGlNativeDisplay.None
