# Palermo Justice

## Overview
Palermo Justice is a mobile app similar to "Mafia" or "Werewolf," where players are assigned secret roles and must work together or against each other to achieve their team's objective. The game is set in a fictional town where Mafia members try to eliminate citizens while remaining undetected, and citizens try to identify and eliminate the Mafia.

## Game Roles

- **Mafioso**: Works with other Mafiosi to eliminate citizens each night
- **Paesano**: Regular citizen trying to survive and vote during the day
- **Ispettore**: Can investigate one player each night to determine if they are Mafia
- **Sgarrista**: Can protect one player each night from elimination
- **Il Prete**: Can bless a player and protect them from elimination

## Project Structure

The project mainly follows a Model-View-Controller (MVC) architecture:

### Model (com.example.palermojustice.model)
- `Game.kt`: Represents the game state and data
- `Player.kt`: Represents a player in the game
- `Role.kt`: Defines different roles and their abilities
- `GameState.kt`: Represents the possible game states
- `RoleAction.kt`: Represents actions that can be performed by specific roles
- `GameResult.kt`: Encapsulates what happened during a phase

### View (com.example.palermojustice.view)
- **Activities**:
  - `MainActivity.kt`: Entry point of the application
  - `CreateGameActivity.kt`: For creating a new game
  - `JoinGameActivity.kt`: For joining an existing game
  - `LobbyActivity.kt`: For waiting in the game lobby
  - `GameActivity.kt`: Main game screen
  - `RoleActionActivity.kt`: For performing role-specific actions
  - `TutorialActivity.kt`: Tutorial for new players

- **Fragments**:
  - `NightPhaseFragment.kt`: UI for night phase
  - `DayPhaseFragment.kt`: UI for day discussion and voting
  - `ResultsFragment.kt`: Displays results of each phase

- **Adapters**:
  - `PlayerAdapter.kt`: Adapter for displaying player lists
  - `VotingAdapter.kt`: Adapter for displaying voting options

### Controller (com.example.palermojustice.controller)
- `GameController.kt`: Main controller for game logic
- `RoleController.kt`: Manages role-specific actions
- `VotingController.kt`: Manages voting process
- `PhaseController.kt`: Controls game phase transitions

### Firebase Integration (com.example.palermojustice.firebase)
- `FirebaseManager.kt`: Manages all interactions with Firebase Realtime Database

### Utils (com.example.palermojustice.utils)
- `Constants.kt`: Game constants and configurations
- `NotificationHelper.kt`: Manages notifications
- `RoleAssignmentManager.kt`: Assigns roles to players

## Game Flow

1. **Lobby**: Players join the game and wait for the host to start
2. **Role Assignment**: Players are secretly assigned roles
3. **Night Phase**: Special roles perform their actions
4. **Night Results**: Results of night actions are revealed
5. **Day Discussion**: Players discuss and try to identify Mafia
6. **Day Voting**: Players vote on who to eliminate
7. **Execution Result**: Result of the vote is revealed
8. **Repeat** steps 3-7 until one team wins

## Setup and Installation

### Prerequisites
- Android Studio
- Android SDK
- Firebase account and project setup
- Kotlin

### Firebase Setup
1. Create a Firebase project in the [Firebase Console](https://console.firebase.google.com/)
2. Add an Android app to your Firebase project
3. Download the `google-services.json` file
4. Place the `google-services.json` file in the app/ directory
5. Create a Realtime Database in your Firebase project
6. Set up the following database rules for testing:
   ```json
   {
     "rules": {
      ".read": "now < 1747000800000",  // 2025-5-12
      ".write": "now < 1747000800000",  // 2025-5-12
     }
   }
   ```
   Note: For production, use more restrictive rules. Firebase only allows access for a certain period of time.

### Building the Project
1. Clone the repository
2. Open the project in Android Studio
3. Sync the project with Gradle files
4. Build the project using `Build > Make Project`

### Running the Application
1. Connect an Android device or use an emulator
2. Run the app using `Run > Run 'app'`
3. The app should install and launch automatically
