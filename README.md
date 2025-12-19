![shimmer_logo](https://github.com/user-attachments/assets/6490fdd3-4b6a-45ba-8b4c-d74d2146cd61)

# Shimmer

A live wallpaper for Android that displays your photos with smooth GPU-based effects.

## Features

- **Image Management**
  - Load images from local folders on your device
  - Support for multiple image folders with random selection
  - Automatic slideshow with configurable timing intervals
  - Smooth crossfade transitions between images
  - Images pan smoothly as you swipe between home screens with parallax effect

- **Performance**
  - All image effects are GPU-accelerated using OpenGL ES 3.0
  - VSync synchronization for smooth 60fps rendering
  - Minimal battery impact with on-demand rendering

### Visual Effects

- **Blur**
  - Adjustable Gaussian blur intensity
  - Optional automatic blur on screen lock
  - Configurable blur timeout

- **Dim**
  - Adjustable dimming overlay for better icon contrast
  - Smoothly animated transitions

- **Film Grain**
  - Vintage film grain texture effect
  - Adjustable grain amount (intensity)
  - Adjustable grain scale (fine to coarse)

- **Duotone**
  - Color tinting with customizable light and dark colors
  - 25+ predefined color presets
  - Three blend modes: Normal, Soft Light, Screen
  - Smooth color interpolation during transitions

- **Chromatic Aberration**
  - Colorful RGB separation distortion effect on touch
  - Multi-touch support (up to 10 simultaneous touch points)

### Gestures & Interactions

- **Touch Gestures**
  - Triple-tap anywhere to toggle blur on/off
  - Two-finger double-tap to advance to next image
  - Touch and drag for chromatic aberration effect (if enabled)

- **Launcher Shortcuts**
  - "Next Image": Advance to the next random image
  - "Random Duotone": Cycle through duotone presets

- **Screen Events**
  - Optional automatic blur on screen lock
  - Optional image change on unlock
  - Pauses slideshow when wallpaper is not visible

### Advanced Settings

- **Transition Control**
  - Configurable slideshow interval
  - Configurable effect transition duration

- **Blur Behavior**
  - Blur timeout with configurable duration (5-60 seconds)
  - Screen lock blur toggle

- **Rendering**
  - Smooth parallax scrolling with exponential smoothing

## Requirements

- Android 8.0 (API 26) or higher
- Storage permissions for accessing image folders

## Technical Details

- Built with Kotlin and Jetpack Compose
- OpenGL ES 3.0 for GPU-accelerated effects
- EGL14 APIs with VSync support
- Custom GLES 3.0 renderer with shader-based effects
- Efficient blur generation using Gaussian blur with downsampling and other perception optimizations


## License

Copyright (c) 2025 Abdussamet Kocak

This work is licensed under the Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License.
