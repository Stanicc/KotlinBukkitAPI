package br.com.devsrsouza.kotlinbukkitapi.extensions.command

import br.com.devsrsouza.kotlinbukkitapi.controllers.CommandController
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.SimpleCommandMap
import org.bukkit.plugin.Plugin
import java.lang.reflect.Field

private val serverCommands: SimpleCommandMap by lazy {
    val server = Bukkit.getServer()
    server::class.java.getDeclaredField("commandMap").apply {
        isAccessible = true
    }.get(server) as SimpleCommandMap
}

private val knownCommandsField: Field by lazy {
    serverCommands.javaClass.getDeclaredField("knownCommands").apply {
        isAccessible = true
    }
}

fun Command.register(plugin: Plugin) {
    serverCommands.register(plugin.name, this)

    val cmds = CommandController.commands.get(plugin.name) ?: mutableListOf()
    cmds.add(this)
    CommandController.commands.put(plugin.name, cmds)
}

fun Command.unregister() {
    try {
        val knownCommands = knownCommandsField.get(serverCommands) as MutableMap<String, Command>
        val toRemove = ArrayList<String>()
        for ((key, value) in knownCommands) {
            if (value === this) {
                toRemove.add(key)
            }
        }
        for (str in toRemove) {
            knownCommands.remove(str)
        }

        CommandController.commands.values.forEach {
            it.removeIf { this === it }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}