import requests
import random
import time
import argparse
import logging
import uuid
import datetime

# Setup logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("Simulator")

API_BASE = "http://localhost:8080/api/v1"

# Words that would typically be banned in the system
BANNED_WORDS = ["badword", "b@dword", "hate", "spam", "scam"]

class Persona:
    def __init__(self, tenant_id, name):
        self.user_id = str(uuid.uuid4())
        self.tenant_id = tenant_id
        self.name = name

    def generate_content(self):
        pass

class NormalUser(Persona):
    def __init__(self, tenant_id):
        super().__init__(tenant_id, "Normal")

    def generate_content(self):
        sentences = [
            "Just having a great day today!",
            "I completely agree with this.",
            "Can someone help me with this problem?",
            "Thanks for the information.",
            "I love this community."
        ]
        return random.choice(sentences)

class TrollUser(Persona):
    def __init__(self, tenant_id):
        super().__init__(tenant_id, "Troll")

    def generate_content(self):
        sentences = [
            f"You all suck! {random.choice(BANNED_WORDS)}",
            "This is the worst thing I've ever seen.",
            f"Shut up {random.choice(BANNED_WORDS)}",
            "I'm just here to ruin your day."
        ]
        return random.choice(sentences)

class BotnetUser(Persona):
    def __init__(self, tenant_id):
        super().__init__(tenant_id, "Botnet")

    def generate_content(self):
        # Botnets might post identical spam or slightly altered spam
        return "Click here to win a free iPhone: http://spam.url " + str(random.randint(1, 1000))


def post_content(persona, content_text):
    payload = {
        "tenant_id": persona.tenant_id,
        "external_user_id": persona.user_id,
        "username": f"{persona.name}_{persona.user_id[:4]}",
        "email": f"{persona.user_id}@test.com",
        "content_text": content_text,
        "content_type": "POST"
    }
    try:
        response = requests.post(f"{API_BASE}/content", json=payload, timeout=5)
        if response.status_code in [200, 201]:
            logger.info(f"[{persona.name}] Posted content successfully.")
        else:
            logger.warning(f"[{persona.name}] Failed to post content: {response.status_code} - {response.text}")
    except requests.exceptions.RequestException as e:
        logger.error(f"[{persona.name}] Request failed: {e}")

def run_simulation(duration_minutes=5, botnet_attack=False):
    tenant_id = "GLOBAL"
    
    # Initialize personas
    normal_users = [NormalUser(tenant_id) for _ in range(10)]
    trolls = [TrollUser(tenant_id) for _ in range(3)]
    bots = [BotnetUser(tenant_id) for _ in range(20)] if botnet_attack else []
    
    all_personas = normal_users + trolls
    
    start_time = datetime.datetime.now()
    end_time = start_time + datetime.timedelta(minutes=duration_minutes)
    
    logger.info(f"Starting simulation for {duration_minutes} minutes. Botnet attack: {botnet_attack}")
    
    while datetime.datetime.now() < end_time:
        if botnet_attack and random.random() < 0.2:
            # Botnet swarm attack
            logger.info("--- BOTNET SWARM ATTACK INITIATED ---")
            for bot in bots:
                post_content(bot, bot.generate_content())
            time.sleep(2) # Cooldown after swarm
            continue
            
        # Normal interaction
        persona = random.choice(all_personas)
        post_content(persona, persona.generate_content())
        
        # Add realistic jitter between posts (1 to 5 seconds)
        time.sleep(random.uniform(1.0, 5.0))
        
    logger.info("Simulation completed.")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Shhmods API Simulator")
    parser.add_argument("--duration", type=int, default=1, help="Duration of simulation in minutes")
    parser.add_argument("--botnet", action="store_true", help="Include a botnet attack")
    args = parser.parse_args()
    
    run_simulation(args.duration, args.botnet)
