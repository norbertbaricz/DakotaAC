package ro.skypixel.play.dakotaAC;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class DakotaAC extends JavaPlugin {

    private static final String PREFIX = "§4§lDAC §c";
    private static final String BASE_PACKAGE_PATH = "ro/skypixel/play/dakotaAC"; // Base package for scanning listeners

    // Punishment manager instance (assuming it's defined elsewhere or you'll add it)
    // private Punishment punishment; // Uncomment if you have a Punishment class

    @Override
    public void onEnable() {
        getLogger().info("Starting DakotaAC plugin...");

        // Initialize your punishment manager here if you have one
        // punishment = new Punishment(this);

        // Register commands
        if (getCommand("dakotaac") != null) {
            getCommand("dakotaac").setExecutor(new Commands(this)); // Assuming Commands class exists
        } else {
            getLogger().warning("Command 'dakotaac' not found in plugin.yml!");
        }

        // Register all listeners automatically
        RegistrationResult listenerResult = registerAllListenersInPackage();
        getLogger().info("Automatic listener registration complete. Registered " + listenerResult.registeredCount + " listeners.");

        // Notify operators about the plugin status and listener registration
        notifyOperators(listenerResult);

        getLogger().info("DakotaAC plugin has been enabled successfully.");
    }

    @Override
    public void onDisable() {
        getLogger().info("DakotaAC plugin has been disabled.");
        // Add any cleanup logic here if needed
    }

    /**
     * Scans the plugin's JAR file for classes within the BASE_PACKAGE_PATH,
     * identifies those that implement Listener, and attempts to register them.
     *
     * @return A RegistrationResult object containing details about the registration process.
     */
    private RegistrationResult registerAllListenersInPackage() {
        int count = 0;
        List<String> loadedClassesNames = new ArrayList<>();
        List<String> errorMessages = new ArrayList<>();
        List<Class<?>> foundClasses = new ArrayList<>();

        try {
            URI pluginUri = getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
            File jarFile = new File(pluginUri);

            if (!jarFile.exists()) {
                String error = "JAR file not found at: " + jarFile.getPath() + ". Cannot scan for listeners.";
                errorMessages.add(error);
                getLogger().severe(error);
                return new RegistrationResult(count, loadedClassesNames, errorMessages);
            }

            // Step 1: Discover all classes within the specified package in the JAR
            try (JarFile jar = new JarFile(jarFile)) {
                java.util.Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    // Check if the entry is a class file within the target package or its sub-packages
                    if (name.startsWith(BASE_PACKAGE_PATH) && name.endsWith(".class")) {
                        String className = name.replace('/', '.').substring(0, name.length() - ".class".length());
                        try {
                            // Load the class without initializing it yet
                            Class<?> clazz = Class.forName(className, false, getClass().getClassLoader());
                            foundClasses.add(clazz);
                        } catch (ClassNotFoundException | NoClassDefFoundError e) {
                            String error = "Could not load class: " + className + ". Error: " + e.getMessage() + ". Check for missing dependencies or typos.";
                            errorMessages.add(error);
                            getLogger().warning(error);
                        }
                    }
                }
            }
            getLogger().info("Found " + foundClasses.size() + " classes to scan in package '" + BASE_PACKAGE_PATH.replace('/', '.') + "'.");

            if (foundClasses.isEmpty() && BASE_PACKAGE_PATH.equals("ro/skypixel/play/dakotaAC")) { // Specific check for the main package
                String infoMsg = "No classes found in the plugin's base package ('" + BASE_PACKAGE_PATH.replace('/', '.') + "'). If you have listeners, ensure they are in this package or its sub-packages and the JAR is built correctly.";
                // This is not necessarily an error, could be a utility plugin with no listeners.
                // errorMessages.add(infoMsg); // Only add as error if listeners are expected
                getLogger().info(infoMsg);
            }

            // Step 2: Instantiate and register listeners
            for (Class<?> clazz : foundClasses) {
                // Check if the class implements Listener, is not an interface, and not an annotation
                if (Listener.class.isAssignableFrom(clazz) && !clazz.isInterface() && !clazz.isAnnotation() && !clazz.isEnum()) {
                    try {
                        Listener listenerInstance = null;
                        // Try to instantiate with a no-argument constructor first
                        try {
                            Constructor<?> constructor = clazz.getDeclaredConstructor();
                            constructor.setAccessible(true); // Ensure accessible if non-public (though listeners usually have public constructors)
                            listenerInstance = (Listener) constructor.newInstance();
                        } catch (NoSuchMethodException e) {
                            // If no-arg constructor fails, try with a constructor that takes the plugin instance
                            try {
                                Constructor<?> pluginConstructor = clazz.getDeclaredConstructor(DakotaAC.class);
                                pluginConstructor.setAccessible(true);
                                listenerInstance = (Listener) pluginConstructor.newInstance(this);
                                getLogger().info("Instantiated listener " + clazz.getSimpleName() + " using plugin instance constructor.");
                            } catch (NoSuchMethodException nsme) {
                                String error = "Failed to instantiate listener " + clazz.getSimpleName() + ": No suitable constructor found (neither default nor one taking DakotaAC plugin instance).";
                                errorMessages.add(error);
                                getLogger().warning(error);
                                continue; // Skip to next class
                            }
                        }

                        if (listenerInstance != null) {
                            getServer().getPluginManager().registerEvents(listenerInstance, this);
                            loadedClassesNames.add(clazz.getSimpleName());
                            getLogger().info("Successfully registered listener: " + clazz.getSimpleName());
                            count++;
                        }
                    } catch (Exception e) {
                        String error = "Failed to register listener: " + clazz.getSimpleName() + ". Error: " + e.getMessage();
                        errorMessages.add(error);
                        getLogger().warning(error);
                        e.printStackTrace(); // Print stack trace for detailed debugging
                    }
                }
            }
        } catch (Exception e) {
            // Catch-all for unexpected errors during the JAR scanning/loading process
            String error = "A critical error occurred while trying to load listeners: " + e.getMessage();
            errorMessages.add(error);
            getLogger().severe(error);
            e.printStackTrace();
        }

        return new RegistrationResult(count, loadedClassesNames, errorMessages);
    }

    /**
     * Notifies online operators about the plugin's status and listener registration results.
     *
     * @param result The RegistrationResult from the listener registration process.
     */
    private void notifyOperators(RegistrationResult result) {
        String statusMessage = PREFIX + "§aPlugin enabled successfully!";
        String listenerCountMessage = PREFIX + "§eLoaded " + result.registeredCount + " listener(s):";

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOp()) {
                player.sendMessage(statusMessage);
                player.sendMessage(listenerCountMessage);

                if (!result.loadedClasses.isEmpty()) {
                    for (String className : result.loadedClasses) {
                        player.sendMessage(PREFIX + "§7  - " + className);
                    }
                } else if (result.registeredCount == 0) {
                    player.sendMessage(PREFIX + "§7  (No listeners were loaded)");
                }

                if (result.errors.isEmpty()) {
                    player.sendMessage(PREFIX + "§aListener registration: No errors detected.");
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                } else {
                    player.sendMessage(PREFIX + "§cListener registration: Errors detected (" + result.errors.size() + "):");
                    for (String error : result.errors) {
                        // Clean up long package names from error messages for operator readability
                        String cleanedError = error.replace("ro.skypixel.play.dakotaAC.", "...");
                        player.sendMessage(PREFIX + "§c  - " + cleanedError);
                    }
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                    player.sendMessage(PREFIX + "§ePlease check the server console for more detailed error information.");
                }
            }
        }
    }

    /**
     * Helper class to store the results of the listener registration process.
     */
    private static class RegistrationResult {
        final int registeredCount;
        final List<String> loadedClasses;
        final List<String> errors;

        RegistrationResult(int registeredCount, List<String> loadedClasses, List<String> errors) {
            this.registeredCount = registeredCount;
            this.loadedClasses = new ArrayList<>(loadedClasses); // Use copies to ensure immutability if needed elsewhere
            this.errors = new ArrayList<>(errors);
        }
    }
}
