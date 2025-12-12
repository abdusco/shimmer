![shimmer_logo](https://github.com/user-attachments/assets/6490fdd3-4b6a-45ba-8b4c-d74d2146cd61)

# Shimmer

A live wallpaper for Android that displays your photos with smooth GPU-based effects.

## Features

- Load images from local folders on your device
- Automatic slideshow with configurable timing 
- Random selection from multiple folders
- Images pan smoothly as you swipe between home screens with a parallax effect
- All image effects are GPU-accelerated using OpenGL ES 2.0 for smooth performance and minimal battery impact

### Visual Effects

- **Blur**: Adjustable Gaussian blur with optional screen lock and idle timeout triggering
- **Dim**: Darken images for better icon contrast
- **Film Grain**: Add vintage film grain texture with adjustable amount and scale
- **Duotone**: Apply color tinting with predefined color schemes or custom colors
- **Chromatic Aberration**: Create colorful distortion effects on touch with fade duration control

### Gestures

- Triple-tap anywhere to toggle blur on/off
- Use launcher shortcuts to advance to next image or cycle duotone presets
  - "Next Image": Advance to the next random image
  - "Random Duotone": Cycle through duotone presets

## Requirements

- Android 6.0 (API 23) or higher
- Storage permissions for accessing image folders

## Technical Details

- Built with Kotlin and Jetpack Compose
- OpenGL ES 2.0 for GPU-accelerated effects

## License

Copyright (c) 2025 Abdussamet Kocak

This work is licensed under the Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License.
