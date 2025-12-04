# Buzei

A live wallpaper for Android that displays your photos with smooth GPU-based effects.

## Features

- Load images from local folders on your device
- Automatic slideshow with configurable timing
- Random selection from multiple folders
- Images pan smoothly as you swipe between home screens with a parallax effect.
- Various visual effects that can be toggled or adjusted:
  - **Blur**: Adjustable Gaussian blur (GPU-accelerated)
  - **Dim**: Darken images for better icon contrast
  - **Duotone**: Apply color tinting with predefined or custom color schemes
- All image effects are GPU-accelerated using OpenGL ES 2.0 for smooth performance and minimal battery impact.

### Gestures

- Triple-tap anywhere to toggle blur on/off
- Use launcher shortcuts to advance to next image or cycle duotone presets
  - "Next Image": Advance to the next random image
  - "Random Duotone": Cycle through duotone presets

## Settings

- Add one or more folders containing images. The wallpaper will randomly select from all images across all folders in a round-robin fashion.
- Enable automatic slideshow and set the interval between image changes (minimum 1 second).
- Control the intensity of the Gaussian blur effect. Set to 0 for no blur.
- Adjust how much the image is darkened. Useful for improving visibility of home screen icons.

## Requirements

- Android 6.0 (API 23) or higher
- Storage permissions for accessing image folders

## Technical Details

- Built with Kotlin and Jetpack Compose
- OpenGL ES 2.0 for GPU-accelerated effects

## License

Copyright (c) 2025 Abdussamet Kocak

This work is licensed under the Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License.
