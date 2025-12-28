![shimmer_logo](https://github.com/user-attachments/assets/6490fdd3-4b6a-45ba-8b4c-d74d2146cd61)

# Shimmer

A modern live wallpaper for Android that transforms your photos with smooth GPU-based effects and transitions.

## Features

- **Dynamic Images**: Load photos from local folders with support for non-repeating random selection and automatic slideshows.
- **Smooth Transitions**: Crossfade animations and parallax scrolling that responds to your home screen swipes.
- **Hardware Accelerated**: Uses OpenGL ES 3.0 for high-performance effects with minimal battery impact.
- **Smart Shuffle**: Shimmer uses a round-robin folder selection and tracks "last shown" timestamps to ensure you see every image in your collection before any repeats occur.

### Visual Effects

- **Gaussian Blur**: Adjustable intensity with smart timeout and screen-lock triggers.
- **Film Grain**: Customizable vintage texture for a tactile feel.
- **Duotone**: Color your images with 25+ presets and multiple blend modes.
- **Chromatic Aberration**: Interactive RGB distortion that reacts to multi-touch gestures.
- **Dimming**: Smoothly dim wallpapers to improve icon and widget readability.

### Interaction

- **Gestures**: Customizable triple-tap and multi-finger double-tap actions (Next Image, Toggle Blur, Random Duotone, Add to Favorites).
- **Sharing**: Support for `ACTION_SEND` and `ACTION_SEND_MULTIPLE`—simply share images from any app to set them as potential wallpapers.
- **Automation**: Integration with system events like screen unlock and lock.

### Special Collections

- **Favorites**: Quickly save your favorite wallpapers to a dedicated folder via gestures or shortcuts.
- **Shared**: Send images directly from your Gallery or other apps to Shimmer using the system "Share" menu. These land in a special "Shared" collection for easy access.

## Advanced: Automation & Intents

Shimmer supports several broadcast intents, allowing you to control the wallpaper from external apps like **Tasker**, **MacroDroid**, or via ADB.

### Actions

| Action | Description | Extras |
| :--- | :--- | :--- |
| `dev.abdus.apps.shimmer.action.NEXT_IMAGE` | Skips to the next random image. | - |
| `dev.abdus.apps.shimmer.action.RANDOM_DUOTONE` | Cycles to a random duotone preset. | - |
| `dev.abdus.apps.shimmer.action.SET_BLUR_PERCENT` | Sets the blur level immediately. | `blur_percent` (Float: 0.0 to 1.0) |
| `dev.abdus.apps.shimmer.action.ENABLE_BLUR` | Forces the wallpaper into blurred state. | - |
| `dev.abdus.apps.shimmer.action.REFRESH_FOLDERS` | Triggers a fresh scan of image folders. | - |
| `dev.abdus.apps.shimmer.action.ADD_TO_FAVORITES` | Saves the current image to your favorites folder. | - |

### Outbound Broadcasts

When an image is added to favorites, Shimmer broadcasts:
`dev.abdus.apps.shimmer.action.FAVORITE_ADDED`
- `favorite_uri`: The URI of the saved file.
- `favorite_display_name`: The filename.

## Requirements

- Android 8.0 (API 26) or higher.
- Storage access for your photo folders.

## License

Copyright (c) 2025 Abdussamet Kocak

Licensed under the [Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License](https://creativecommons.org/licenses/by-nc-sa/4.0/).