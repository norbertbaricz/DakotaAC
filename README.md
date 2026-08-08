<div align="center">
  <h1>🛡️ DakotaAC</h1>
  <p><b>Maximum Protection for Your Minecraft Server</b></p>
</div>

DakotaAC is an advanced, highly optimized anti-cheat plugin specifically designed to provide efficient protection against cheaters and exploiters on Minecraft servers. It monitors and prevents suspicious activities in real-time, ensuring a balanced and fair gaming environment for all players. With an intelligent detection system and an easy-to-use interface, DakotaAC is the ideal solution for server administrators who want to maintain a safe and enjoyable gaming community.

---

### ⚠️ Important Requirements
DakotaAC relies on the following plugins to function correctly:
- **[Citizens2](https://ci.citizensnpcs.co/job/Citizens2/)** (Version v0.14 or below)
- **[ProtocolLib](https://www.spigotmc.org/resources/protocollib.1997/)** (Version v0.15 or above)

---

### 🎯 Recommended Server Types
Thanks to its advanced and highly optimized detection systems, DakotaAC seamlessly supports both standard survival gameplay and fast-paced competitive modes. It is highly recommended for:
1. **Survival & SMP** (Survival Multiplayer)
2. **PvP & Practice** 
3. **Minigames** (SkyWars, BedWars, etc.)
4. **Factions**
5. **UHC** (Ultra Hardcore)

---

### 🚀 Massive Update & Key Features
With the latest massive update, DakotaAC's detection systems have been completely overhauled for maximum precision, performance optimization, and fewer false positives! 

#### ⚔️ Combat Checks
*   **AimBot:** Spawns a fake NPC to circle the player and detects unnatural automatic targeting.
*   **HitBox:** Precise server-side hitbox intersection utilizing `getBoundingBox().rayTrace(...)` to detect exact aim alignment.
*   **KillAura:** Detects players attempting to attack entities through solid or non-solid blocks.
*   **Critical:** Prevents unnatural critical hits where the player's Y-coordinate doesn't change smoothly.

#### 🏃 Movement Checks
*   **Speed / OmniSprint:** Dynamically calculates max allowed speed per tick, properly factoring in Speed/Slowness potion multipliers.
*   **HighJump:** Checks for abrupt Y-coordinate changes and calculates maximum allowed jump height (accounting for Jump Boost and Slime Blocks).
*   **NoSlowDown:** Detects players who eat or block (with a shield) without suffering the standard XZ movement speed penalty.
*   **Velocity:** Prevents Anti-Knockback hacks, ensuring players receive proper knockback direction and distance.

#### 🌍 World & Player Checks
*   **Scaffold:** Detects unnaturally fast block placement and single-tick multi-block placements.
*   **AntiVoid:** Prevents void-fall exploitation by monitoring the player's Y-level and returning them safely if criteria are met.
*   **ChestStealer:** Two-stage detection measuring click speed and total items taken to block inventory-stealing macros.
*   **Fucker & Jesus:** Detects breaking blocks through interference and walking incorrectly on non-solid blocks (like water or lava).

#### 🛠️ General System Benefits
*   **Fast and effective detection:** Logs all cheating attempts for better incident management.
*   **Highly configurable:** Easily adapt the configuration to suit the unique needs of your server, offering maximum flexibility.
*   **User-friendly interface:** Comes with customizable warning notifications and alerts.
*   **Regular updates:** Continuously and actively developed to combat the latest cheating methods.

---

### 📥 Benefits of Using DakotaAC
1. **Keeps your server clean and fair** for all honest players.
2. **Reduces administrative time** spent hunting down and managing cheaters.
3. **Ensures an enjoyable and competitive gameplay experience.**

Install DakotaAC and protect your server with the latest anti-cheat technology, easy to implement and configure for your needs!

---

### 💬 Feedback & Support
Found a bug, have a suggestion, or want to provide feedback? Please submit it here:  
**🔗 [Submit Feedback](https://forms.gle/AiuR8aTALQmXWnfu9)**

---

### 👨‍💻 About the Developer
Hi, my name is Max, and I am the solo developer behind DakotaAC! I am a beginner in creating and posting my plugins on the internet. Whether I'm diving into Java to improve this anti-cheat or creating gaming content as MaxUltimat3 for my streaming community, I put my heart and soul into my projects. If my plugins don't meet your expectations yet, please know that I am working hard on them every day because I absolutely love coding for the Minecraft community!