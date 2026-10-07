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
BANNED_WORDS = ["badword", "b@dword", "hate", "spam", "scam"]

class Persona:
    def __init__(self, tenant_id, name):
        self.user_id = str(uuid.uuid4())
        self.tenant_id = tenant_id
        self.name = name

class NormalUser(Persona):
    def __init__(self, tenant_id):
        super().__init__(tenant_id, "Normal")
    def generate_content(self):
        return random.choice([
            "Just having a great day today!",
            "Can someone help me with this problem?",
            "Thanks for the information."
        ])

class TrollUser(Persona):
    def __init__(self, tenant_id):
        super().__init__(tenant_id, "Troll")
    def generate_content(self):
        return f"You all suck! {random.choice(BANNED_WORDS)}"

class BotnetUser(Persona):
    def __init__(self, tenant_id):
        super().__init__(tenant_id, "Botnet")
    def generate_content(self):
        return "Click here to win a free iPhone: http://spam.url" # Identical hash for graph coordination

def post_content(persona, content_text):
    payload = {
        "tenantId": persona.tenant_id,
        "externalUserId": persona.user_id,
        "username": f"{persona.name}_{persona.user_id[:4]}",
        "email": f"{persona.user_id}@test.com",
        "contentText": content_text,
        "contentType": "POST"
    }
    try:
        response = requests.post(f"{API_BASE}/content", json=payload, timeout=5)
        if response.status_code in [200, 201]:
            logger.info(f"[{persona.name}] Posted content. Response: {response.json().get('decision', 'SUCCESS')}")
            return response.json().get('content_id')
        else:
            logger.warning(f"[{persona.name}] Failed: {response.status_code}")
    except Exception as e:
        logger.error(f"Request failed: {e}")
    return None

def submit_report(persona, content_id):
    payload = {
        "contentId": content_id,
        "reporterId": persona.user_id,
        "reportReason": "Spam or malicious content",
        "tenantId": persona.tenant_id
    }
    try:
        response = requests.post(f"{API_BASE}/report", json=payload, timeout=5)
        logger.info(f"[{persona.name}] Reported content {content_id}. Status: {response.status_code}")
    except Exception as e:
        logger.error(f"Report failed: {e}")

def run_simulation(duration_minutes=5, botnet_attack=False, brigade_attack=False, test_overrides=False):
    global_tenant = "GLOBAL"
    custom_tenant = "TENANT_A" # To test overrides
    
    normal_users = [NormalUser(global_tenant) for _ in range(5)]
    trolls = [TrollUser(global_tenant) for _ in range(2)]
    bots = [BotnetUser(global_tenant) for _ in range(10)] if botnet_attack else []
    
    all_personas = normal_users + trolls
    start_time = datetime.datetime.now()
    end_time = start_time + datetime.timedelta(minutes=duration_minutes)
    
    logger.info("--- STARTING SHHMODS SIMULATION ---")
    
    # 1. Test Tenant Override (Bypass URL Filter)
    if test_overrides:
        logger.info("Testing Tenant Policy Override (Trusted User bypasses URL block)...")
        trusted_vet = NormalUser(custom_tenant)
        # We assume trusted_vet has 100 trust by default. Posting a URL should bypass if policy allows.
        post_content(trusted_vet, "Check out my legitimate portfolio: http://my-portfolio.com")
        time.sleep(1)

    while datetime.datetime.now() < end_time:
        # 2. Botnet Spam Attack (Tests Content Clusters / Burst Limits)
        if botnet_attack and random.random() < 0.2:
            logger.info("--- BOTNET SWARM ATTACK INITIATED ---")
            for bot in bots:
                post_content(bot, bot.generate_content()) # All post identical spam
            time.sleep(2)
            continue
            
        # 3. Brigading Attack (Tests Coordination Clusters / Mass Reports)
        if brigade_attack and random.random() < 0.1:
            logger.info("--- BRIGADE MASS-REPORT INITIATED ---")
            target_post_id = post_content(normal_users[0], "Just a normal post, doing nothing wrong.")
            if target_post_id:
                time.sleep(1)
                for troll in trolls + bots[:3]: # Swarm of attackers reporting innocent user
                    submit_report(troll, target_post_id)
            time.sleep(2)
            continue
            
        # Normal interaction
        persona = random.choice(all_personas)
        post_content(persona, persona.generate_content())
        time.sleep(random.uniform(0.5, 2.0))
        
    logger.info("--- SIMULATION COMPLETED ---")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--duration", type=int, default=1)
    parser.add_argument("--botnet", action="store_true", help="Test content clustering & spam")
    parser.add_argument("--brigade", action="store_true", help="Test coordination clusters (mass reports)")
    parser.add_argument("--overrides", action="store_true", help="Test tenant policy overrides")
    args = parser.parse_args()
    
    run_simulation(args.duration, args.botnet, args.brigade, args.overrides)
