classDiagram
    %% Model Package
    class Game {
        +id: String
        +hostId: String
        +gameCode: String
        +status: String
        +players: Map<String, Player>
        +fromSnapshot()
    }
    
    class Player {
        +id: String
        +name: String
        +role: String
        +isAlive: Boolean
        +toMap()
    }
    
    class Role {
        <<enumeration>>
        MAFIOSO
        PAESANO
        ISPETTORE
        SGARRISTA
        IL_PRETE
        +canActAtNight()
        +getNightActionDescription()
    }
    
    class GameState {
        <<enumeration>>
        LOBBY
        ROLE_ASSIGNMENT
        NIGHT
        NIGHT_RESULTS
        DAY_DISCUSSION
        DAY_VOTING
        EXECUTION_RESULT
        GAME_OVER
        +getNextState()
    }
    
    class GameResult {
        +phaseNumber: Int
        +state: GameState
        +eliminatedPlayerId: String
        +investigationResult: Boolean
        +protectedPlayerId: String
        +winningTeam: Team
        +toMap()
    }
    
    class RoleAction {
        +actionType: ActionType
        +sourcePlayerId: String
        +targetPlayerId: String
        +phaseNumber: Int
        +toMap()
    }
    
    %% Controller Package
    class GameController {
        -currentGame: Game
        -currentState: GameState
        -currentPhaseNumber: Int
        +startGame()
        +advancePhase()
        +performRoleAction()
        +submitVote()
        +checkGameOver()
    }
    
    class PhaseController {
        +beginNightPhase()
        +processNightActions()
        +beginDayPhase()
        +beginVotingPhase()
        +processVotingResults()
    }
    
    class RoleController {
        +submitAction()
        +validateAction()
    }
    
    class VotingController {
        +submitVote()
        +tallyVotes()
    }
    
    %% View Package
    class GameActivity {
        +onGameStateChanged()
        +onGameResultReceived()
        +showNightPhase()
        +showDayPhase()
        +showResultsPhase()
    }
    
    class LobbyActivity {
        +listenToGameUpdates()
        +updatePlayersList()
    }
    
    class RoleActionActivity {
        +updatePlayersList()
        +submitAction()
    }
    
    %% Firebase Package
    class FirebaseManager {
        +getCurrentUserId()
        +createGame()
        +joinGameByCode()
        +listenToGame()
        +leaveGame()
    }
    
    %% Relationships
    Game "1" *-- "many" Player
    Player -- Role
    GameController *-- PhaseController
    GameController *-- RoleController
    GameController *-- VotingController
    GameController -- Game
    PhaseController -- GameResult
    
    GameActivity --> GameController : uses
    LobbyActivity --> GameController : uses
    RoleActionActivity --> GameController : uses
    
    GameController --> FirebaseManager : uses
    PhaseController --> FirebaseManager : uses
    RoleController --> FirebaseManager : uses
    VotingController --> FirebaseManager : uses
    
    LobbyActivity --> FirebaseManager : uses
    GameActivity --> FirebaseManager : uses
    RoleActionActivity --> FirebaseManager : uses
