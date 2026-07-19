# cover-map
Small Android app where you can highlight places on the world map.
Main use is to track where you've been or plan where you want to go.

All data synced to your Google Drive.
You can use it without syncing and keep data locally but they may get wiped once you sync.

![example1](/example/example1.png)
![example2](/example/example2.png)

## set up
- add **android** folder as a new project in Android Studio.
- (optional) create a new project on [Google Cloud](https://console.cloud.google.com)
- (optional) create a new client -> android -> package name (here it's com.covermap.app) -> SHA-1 certificate (check online how to get it)
- that's it. 

After any changes in [www](/www) folder, run `npx cap sync android` to update the android project.

## functionalities
- change all highlights color / opacity
- brush size
- stroke eraser
- load / save to GDrive
- zoomed out visibility
- highlighted area percentage

## security & bugs
That's a quick project made just for fun.
There might be some security issues or bugs that i missed.