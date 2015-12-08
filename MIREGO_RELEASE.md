# Mirego manual release process

## Setup release version number

1. Edit `gradle.properties` and remove `-SNAPSHOT`from version number:
     - Assuming the current version number is `3.3.1.2-mirego-SNAPSHOT`
     - change it to `3.3.1.2-mirego` 
2. Commit the file with comment and push it to origin server:
     - `prepare release: 3.3.1.2-mirego`
3. Create a new tag from this commit with the current version number: `3.3.1.2-mirego`

## Perform release
Perform the following gradle command:

- `./gradlew install uploadArchives`


## Setup next release version number
1. Edit `gradle.properties`, increment version number and add `-SNAPSHOT`suffix:
    - `3.3.1.3-mirego-SNAPSHOT`
2. Commit the file with comment:
    - `prepare for next development iteration`
  





 
