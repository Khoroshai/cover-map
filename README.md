# cover-map
Small Android app where you can highlight places on the world map.
Main use is to track where you've been or plan where you want to go.

All data synced to your Google Drive.
You can use it without syncing and keep data locally but they may get wiped once you sync.

![example1](/example/example1.png)
![example2](/example/example2.png)

## set up
- add **android** folder as a new project in Android Studio.
- create a new project on [App Script](https://script.google.com)
- paste [gdrive-script](/gdrive-script.gs)'s content into your new project
- deploy -> new deployement -> web app -> user accessing the web app -> all users owning a Google account (or *me only* depending on your preference)
- change [index](/www/index.html) at *GDRIVE_SCRIPT_URL* with yours.
- that's it. 

After any changes in [www](/www) folder, run `npx cap sync android` to update the android project.

After any changes in [gdrive-script](gdrive-script.gs), paste it to your App Script and save. Then *manage deployement* -> select yours, edit (pencil icon) -> change version to *new version*.

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