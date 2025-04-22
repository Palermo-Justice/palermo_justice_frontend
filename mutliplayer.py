import firebase_admin
from firebase_admin import credentials, db
import random
import time
import argparse
import threading

# CONFIG
SERVICE_ACCOUNT_PATH = 'serviceAccountKey.json'
DATABASE_URL = 'https://palermojusticetest-default-rtdb.europe-west1.firebasedatabase.app/'  # Replace with your URL

# Game state constants
class GameState:
    LOBBY = "lobby"
    NIGHT = "night"
    NIGHT_RESULTS = "night_results"
    DAY_DISCUSSION = "day"
    DAY_VOTING = "voting"
    EXECUTION_RESULTS = "execution_results"
    GAME_OVER = "finished"
    
# Role constants
class Role:
    MAFIOSO = "MAFIOSO"
    PAESANO = "PAESANO" 
    ISPETTORE = "ISPETTORE"
    SGARRISTA = "SGARRISTA"
    IL_PRETE = "IL_PRETE"
    
# Action types
class ActionType:
    KILL = "KILL"
    INVESTIGATE = "INVESTIGATE"
    PROTECT = "PROTECT"
    BLESS = "BLESS"
    VOTE = "VOTE"

# Initialize Firebase
cred = credentials.Certificate(SERVICE_ACCOUNT_PATH)
firebase_admin.initialize_app(cred, {
    'databaseURL': DATABASE_URL
})

def generate_guest_id():
    """Generate a random guest ID"""
    return f"guest_{random.randint(100000, 999999)}"

def generate_player_name(existing_names=None):
    """Generate a random name for a virtual player that doesn't exist yet in the game
    
    Args:
        existing_names: Set of names already used in the game
    """
    if existing_names is None:
        existing_names = set()
        
    first_names = [
        "Alex", "Blair", "Casey", "Dana", "Elliot", "Fran", "Glenn", 
        "Harper", "Indigo", "Jordan", "Kyle", "Logan", "Morgan", 
        "Nico", "Parker", "Quinn", "Riley", "Sage", "Taylor", "Val",
        "Avery", "Bailey", "Cameron", "Devin", "Eden", "Finley", "Gray",
        "Hayden", "Ivy", "Jamie", "Kendall", "Lee", "Madison", "Noah",
        "Oakley", "Peyton", "Reese", "Skyler", "Tristan", "Winter"
    ]
    
    # Filter out names that are already taken
    available_names = [name for name in first_names if name not in existing_names]
    
    if not available_names:
        # If all names are taken, add a random number suffix
        name = random.choice(first_names)
        suffix = random.randint(1, 99)
        return f"{name}{suffix}"
    
    return random.choice(available_names)

def find_available_games():
    """Find all available games with virtualPlayersEnabled flag set to true"""
    games_ref = db.reference('games')
    games = games_ref.get() or {}
    
    available_games = []
    
    for game_id, game_data in games.items():
        # Check if the game is in lobby state
        is_lobby = game_data.get('status') == 'lobby'
        
        # Check if virtual players are enabled
        virtual_enabled = bool(game_data.get('virtualPlayersEnabled'))
        
        if is_lobby and virtual_enabled:
            available_games.append({
                'id': game_id,
                'players': len(game_data.get('players', {}))
            })
    
    return available_games

def check_virtual_players_enabled(game_id):
    """Check if virtual players are enabled for the given game"""
    game_ref = db.reference(f'games/{game_id}')
    flags = game_ref.child('virtualPlayersEnabled').get()
    
    # Convert to boolean and handle None case
    return bool(flags)

class VirtualPlayer:
    """Class to manage a virtual player's actions in the game"""
    
    def __init__(self, game_id, player_id, player_name, role=None):
        self.game_id = game_id
        self.player_id = player_id
        self.player_name = player_name
        self.role = role
        self.is_alive = True
        self.game_ref = db.reference(f'games/{game_id}')
        self.player_ref = self.game_ref.child(f'players/{player_id}')
        self.actions_ref = self.game_ref.child('actions')
        
    def update_status(self):
        """Update player status from database"""
        player_data = self.player_ref.get() or {}
        self.role = player_data.get('role')
        self.is_alive = player_data.get('isAlive', True)
        return self.is_alive and self.role is not None
        
    def perform_night_action(self, phase_number):
        """Perform appropriate night action based on role"""
        if not self.is_alive or not self.role:
            return False
            
        # Get all alive players
        players_data = self.game_ref.child('players').get() or {}
        alive_players = [
            player_id for player_id, data in players_data.items() 
            if player_id != self.player_id and data.get('isAlive', True)
        ]
        
        if not alive_players:
            print(f"Game {self.game_id}: No valid targets for {self.player_name}")
            return False
            
        # Choose a random target
        target_id = random.choice(alive_players)
        
        # Determine action type based on role
        action_type = None
        if self.role == Role.MAFIOSO:
            action_type = ActionType.KILL
            # Filter out other mafia members
            non_mafia_players = [
                player_id for player_id, data in players_data.items()
                if (player_id != self.player_id and 
                    data.get('isAlive', True) and 
                    data.get('role') != Role.MAFIOSO)
            ]
            if non_mafia_players:
                target_id = random.choice(non_mafia_players)
        elif self.role == Role.ISPETTORE:
            action_type = ActionType.INVESTIGATE
        elif self.role == Role.SGARRISTA:
            action_type = ActionType.PROTECT
            # Can protect self
            if random.random() < 0.3:  # 30% chance to protect self
                target_id = self.player_id
        elif self.role == Role.IL_PRETE:
            action_type = ActionType.BLESS
            # Can bless self
            if random.random() < 0.3:  # 30% chance to bless self
                target_id = self.player_id
        else:
            # Regular citizens don't have night actions
            return False
            
        if action_type:
            # Submit the action
            action_data = {
                'actionType': action_type,
                'sourcePlayerId': self.player_id,
                'targetPlayerId': target_id,
                'timestamp': int(time.time() * 1000)
            }
            
            self.actions_ref.child(f'night/{phase_number}/{self.player_id}').set(action_data)
            print(f"Game {self.game_id}: Player {self.player_name} ({self.role}) performed {action_type} on target {target_id}")
            return True
            
        return False
        
    def perform_day_vote(self, phase_number):
        """Vote during the day phase"""
        if not self.is_alive:
            return False
            
        # Get all alive players
        players_data = self.game_ref.child('players').get() or {}
        alive_players = [
            player_id for player_id, data in players_data.items() 
            if player_id != self.player_id and data.get('isAlive', True)
        ]
        
        if not alive_players:
            print(f"Game {self.game_id}: No valid targets for {self.player_name} to vote")
            return False
            
        # Choose a random target
        target_id = random.choice(alive_players)
        
        # If player is mafia, try to avoid voting for other mafia
        if self.role == Role.MAFIOSO:
            non_mafia_players = [
                player_id for player_id, data in players_data.items()
                if (player_id != self.player_id and 
                    data.get('isAlive', True) and 
                    data.get('role') != Role.MAFIOSO)
            ]
            if non_mafia_players:
                target_id = random.choice(non_mafia_players)
        
        # Submit the vote
        action_data = {
            'actionType': ActionType.VOTE,
            'sourcePlayerId': self.player_id,
            'targetPlayerId': target_id,
            'timestamp': int(time.time() * 1000)
        }
        
        self.actions_ref.child(f'day/{phase_number}/{self.player_id}').set(action_data)
        print(f"Game {self.game_id}: Player {self.player_name} voted for player {target_id}")
        return True

class GameManager:
    """Class to manage a game and its virtual players"""
    
    def __init__(self, game_id):
        self.game_id = game_id
        self.game_ref = db.reference(f'games/{game_id}')
        self.virtual_players = {}  # player_id -> VirtualPlayer
        self.our_virtual_players = set()  # Set of player IDs that THIS SCRIPT created
        self.game_state = GameState.LOBBY
        self.current_phase = 0
        self.listener = None
        self.flag_listener = None
        self.active = True
        
    def start_monitoring(self):
        """Start monitoring the game for state changes"""
        def on_game_change(event):
            if not event.data or not self.active:
                return
                
            # Handle both dictionary and string/primitive event data types
            if isinstance(event.data, dict):
                # Get current game state from the dictionary
                state = event.data.get('status', GameState.LOBBY)
                phase = event.data.get('currentPhase', 0)
                
                # Check if state changed
                if state != self.game_state or phase != self.current_phase:
                    old_state = self.game_state
                    self.game_state = state
                    self.current_phase = phase
                    print(f"Game {self.game_id}: State changed from {old_state} to {state}, phase {phase}")
                    
                    # Update player roles
                    self.update_players()
                    
                    # Perform actions based on state
                    self.handle_state_change(old_state)
            else:
                # If it's a string or primitive type, it's likely a value update at a specific path
                # We can get the full game data to handle this case
                try:
                    game_data = self.game_ref.get()
                    if game_data and isinstance(game_data, dict):
                        state = game_data.get('status', GameState.LOBBY)
                        phase = game_data.get('currentPhase', 0)
                        
                        if state != self.game_state or phase != self.current_phase:
                            old_state = self.game_state
                            self.game_state = state
                            self.current_phase = phase
                            print(f"Game {self.game_id}: State changed from {old_state} to {state}, phase {phase}")
                            
                            # Update player roles
                            self.update_players()
                            
                            # Perform actions based on state
                            self.handle_state_change(old_state)
                except Exception as e:
                    print(f"Game {self.game_id}: Error handling game change event: {e}")
        
        # Set up flag listener to detect when virtual players are disabled
        def on_flag_change(event):
            if not self.active:
                return
            
            try:
                # Handle both dictionary and primitive event data types
                if isinstance(event.data, dict):
                    virtual_enabled = bool(event.data.get('virtualPlayersEnabled', False))
                else:
                    # Direct value update
                    virtual_enabled = bool(event.data)
                    
                if not virtual_enabled:
                    print(f"Game {self.game_id}: Virtual players disabled - removing all virtual players")
                    # Schedule removal on a separate thread to avoid the "cannot join current thread" error
                    threading.Timer(0.1, self.remove_all_virtual_players).start()
                    # Mark as inactive, but don't try to close listeners from this callback
                    self.active = False
                    # Schedule the actual listener cleanup on a separate thread
                    threading.Timer(0.5, self.cleanup_listeners).start()
            except Exception as e:
                print(f"Game {self.game_id}: Error handling flag change event: {e}")
                
        # Set up listeners
        self.listener = self.game_ref.listen(on_game_change)
        self.flag_listener = self.game_ref.child('virtualPlayersEnabled').listen(on_flag_change)
    
    def cleanup_listeners(self):
        """Clean up listeners safely (called from a separate thread)"""
        try:
            if self.listener:
                self.listener.close()
                self.listener = None
            if self.flag_listener:
                self.flag_listener.close()
                self.flag_listener = None
        except Exception as e:
            print(f"Game {self.game_id}: Error cleaning up listeners: {e}")
    
    def stop_monitoring(self):
        """Stop monitoring the game"""
        self.active = False
        # Schedule listener cleanup on a separate thread
        threading.Timer(0.1, self.cleanup_listeners).start()
            
    def update_players(self):
        """Update virtual players with current data and identify new virtual players"""
        players_data = self.game_ref.child('players').get() or {}
        
        # Update existing virtual players we're tracking
        for player_id in list(self.virtual_players.keys()):
            if player_id in players_data:
                self.virtual_players[player_id].update_status()
            else:
                # Player no longer exists in the game
                del self.virtual_players[player_id]
                
        # Find new virtual players that we created but aren't tracking yet
        for player_id, player_data in players_data.items():
            # Only consider players with IDs that start with "guest_" as virtual players
            if (player_id not in self.virtual_players and 
                player_id.startswith('guest_') and
                'name' in player_data):
                
                # Add new virtual player to our tracking
                self.virtual_players[player_id] = VirtualPlayer(
                    self.game_id,
                    player_id,
                    player_data.get('name', 'Unknown'),
                    player_data.get('role')
                )
                print(f"Game {self.game_id}: Now tracking virtual player {player_id} ({player_data.get('name')})")

                
    def handle_state_change(self, old_state):
        """Handle game state changes and perform appropriate actions"""
        print(f"Game {self.game_id}: Handling state change to {self.game_state}")
        
        if self.game_state == GameState.NIGHT:
            # Perform night actions after a short delay
            threading.Timer(3.0, self.perform_night_actions).start()
            
        elif self.game_state == GameState.DAY_VOTING:
            # Perform day voting after a short delay
            threading.Timer(3.0, self.perform_day_voting).start()
            
        elif self.game_state == GameState.GAME_OVER:
            print(f"Game {self.game_id}: Game over!")
            self.stop_monitoring()
            
    def perform_night_actions(self):
        """Have virtual players perform their night actions"""
        print(f"Game {self.game_id}: Performing night actions for phase {self.current_phase}")
        
        # Only perform actions for virtual players CREATED BY THIS SCRIPT
        for player_id, player in self.virtual_players.items():
            if player.is_alive and player_id in self.our_virtual_players:
                player.perform_night_action(self.current_phase)
                # Add small delay to make it look natural
                time.sleep(random.uniform(0.5, 1.5))
                
    def perform_day_voting(self):
        """Have virtual players cast their votes"""
        print(f"Game {self.game_id}: Performing day voting for phase {self.current_phase}")
        
        # Only perform actions for virtual players CREATED BY THIS SCRIPT
        for player_id, player in self.virtual_players.items():
            if player.is_alive and player_id in self.our_virtual_players:
                player.perform_day_vote(self.current_phase)
                # Add small delay to make it look natural
                time.sleep(random.uniform(0.5, 1.5))
                
    def add_virtual_player(self, player_id, player_name, created_by_us=False):
        """Add a new virtual player to the game
        
        Args:
            player_id: The player ID
            player_name: The player name
            created_by_us: Whether this player was created by this script instance
        """
        virtual_player = VirtualPlayer(self.game_id, player_id, player_name)
        self.virtual_players[player_id] = virtual_player
        
        # If we created this player, track it in our set
        if created_by_us:
            self.our_virtual_players.add(player_id)
            print(f"Game {self.game_id}: Tracking player {player_id} as created by this script")
            
        return virtual_player
        
    def remove_all_virtual_players(self):
        """Remove all virtual players from the game"""
        if not self.our_virtual_players:
            return
            
        print(f"Game {self.game_id}: Removing {len(self.our_virtual_players)} virtual players created by this script...")
        
        # First get current host ID to avoid removing host
        host_id = self.game_ref.child('hostId').get()
        
        # For each virtual player that WE created, remove it
        for player_id in list(self.our_virtual_players):
            # Skip if this virtual player is somehow the host
            if player_id == host_id:
                print(f"Game {self.game_id}: Virtual player {player_id} is the host - cannot remove")
                continue
                
            try:
                # Remove the player from Firebase - using the correct path
                db.reference(f'games/{self.game_id}/players/{player_id}').set(None)
                print(f"Game {self.game_id}: Removed virtual player {player_id}")
                
                # Remove from our local tracking
                self.our_virtual_players.remove(player_id)
                if player_id in self.virtual_players:
                    del self.virtual_players[player_id]
            except Exception as e:
                print(f"Game {self.game_id}: Error removing player {player_id}: {e}")
        
        print(f"Game {self.game_id}: All virtual players created by this script have been removed")

def process_single_game(game_id, total_players):
    """Process a single game by adding virtual players if needed and monitor gameplay"""
    # First check if virtual players are enabled
    if not check_virtual_players_enabled(game_id):
        print(f"Game {game_id}: Virtual players are not enabled. Skipping.")
        return False
    
    game_ref = db.reference(f'games/{game_id}')
    players_ref = game_ref.child('players')

    existing_players = players_ref.get() or {}
    
    # Count real players and track names for uniqueness
    real_player_count = len(existing_players)
    existing_names = set()
    
    for player_data in existing_players.values():
        if 'name' in player_data:
            existing_names.add(player_data['name'])

    print(f"🎮 Game {game_id}: {real_player_count} existing players found")

    # Calculate how many virtual players to add
    total_desired = total_players
    virtual_players_to_add = max(0, total_desired - real_player_count)
    
    # Create a game manager to monitor and control virtual players
    game_manager = GameManager(game_id)
    
    # Add new virtual players if needed
    if virtual_players_to_add > 0:
        print(f"➕ Game {game_id}: Adding {virtual_players_to_add} virtual players")
        
        for _ in range(virtual_players_to_add):
            player_id = generate_guest_id()
            player_name = generate_player_name(existing_names)
            # Add the name to our tracking set to prevent duplicates within the same batch
            existing_names.add(player_name)
            
            player_data = {
                'id': player_id,
                'name': player_name,
                'role': None,
                'isAlive': True
            }
            players_ref.child(player_id).set(player_data)
            print(f"🤖 Game {game_id}: Added virtual player: {player_id} with name {player_name}")
            
            # Add to game manager AND mark as created by us
            game_manager.add_virtual_player(player_id, player_name, created_by_us=True)

        print(f"Game '{game_id}' now has {real_player_count + virtual_players_to_add} total players")
    else:
        print(f"Game {game_id}: No need to add more virtual players, starting to monitor gameplay")
    
    # Start monitoring
    game_manager.start_monitoring()
    
    return game_manager

def fill_game_room_with_guests(game_id=None, total_players=4):
    """Add virtual players to the game if enabled and monitor their gameplay
    
    If no game_id is provided, it will find available games automatically
    
    Returns:
        A list of active game managers
    """
    game_managers = []
    
    if not game_id:
        # Find available games
        available_games = find_available_games()
        
        if not available_games:
            print("🔍 No available games found with virtualPlayersEnabled flag. Will check again later.")
            return game_managers  # Return empty list but don't exit
        
        # Process all available games
        for game in available_games:
            game_id = game['id']
            print(f"🎮 Processing game: {game_id}")
            game_manager = process_single_game(game_id, total_players)
            if game_manager:
                game_managers.append(game_manager)
        
        return game_managers
    else:
        # Process just the specified game
        game_manager = process_single_game(game_id, total_players)
        if game_manager:
            game_managers.append(game_manager)
        return game_managers

# === EXECUTION ===
if __name__ == "__main__":
    # Parse command line arguments
    parser = argparse.ArgumentParser(description='Add virtual players to Palermo Justice games and automate gameplay')
    parser.add_argument('--total', type=int, default=4, help='Total number of players desired in each game')
    parser.add_argument('--game-id', type=str, default=None, help='Specific game ID to join (optional)')
    parser.add_argument('--monitor', action='store_true', help='Continuously monitor for new games')
    parser.add_argument('--interval', type=int, default=5, help='Polling interval in seconds (default: 30)')
    args = parser.parse_args()
    
    try:
        active_game_managers = []
        
        # Always run in continuous monitoring mode
        print(f"Starting monitoring mode, checking for games every {args.interval} seconds...")
        while True:
            try:
                # First, clean up any game managers for games that have finished
                active_game_managers = [gm for gm in active_game_managers if gm.active]
                
                # Then look for new games
                if args.game_id:
                    new_managers = fill_game_room_with_guests(args.game_id, total_players=args.total)
                else:
                    # Auto-detect and join all available games
                    new_managers = fill_game_room_with_guests(total_players=args.total)
                
                # Add new managers to our active list
                for manager in new_managers:
                    if manager not in active_game_managers:
                        active_game_managers.append(manager)
                
                print(f"📊 Currently monitoring {len(active_game_managers)} active games")
            except Exception as e:
                print(f"Error during monitoring cycle: {e}")
                # Continue running even after errors
            
            print(f"⏳ Waiting {args.interval} seconds before next check...")
            time.sleep(args.interval)
                
    except KeyboardInterrupt:
        print("Monitoring stopped by user")
    except Exception as e:
        print(f"Critical error occurred: {e}")
    finally:
        # Clean up all game managers
        for gm in active_game_managers:
            if hasattr(gm, 'stop_monitoring'):
                gm.stop_monitoring()
                
        print("Script execution completed")