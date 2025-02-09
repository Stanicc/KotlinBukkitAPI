package br.com.devsrsouza.kotlinbukkitapi.dsl.command

import br.com.devsrsouza.kotlinbukkitapi.extensions.text.*
import br.com.devsrsouza.kotlinbukkitapi.extensions.command.*
import br.com.devsrsouza.kotlinbukkitapi.extensions.plugin.WithPlugin
import net.md_5.bungee.api.chat.BaseComponent
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

typealias ExecutorBlock = Executor<CommandSender>.() -> Unit
typealias ExecutorPlayerBlock = Executor<Player>.() -> Unit
typealias TabCompleterBlock = TabCompleter.() -> List<String>
typealias CommandMaker = CommandDSL.() -> Unit

class CommandException(
        val senderMessage: BaseComponent? = null,
        val argMissing: Boolean = false,
        val execute: () -> Unit = {}
) : RuntimeException() {
    constructor(senderMessage: String = "", argMissing: Boolean = false, execute: () -> Unit = {})
            : this(senderMessage.takeIf { it.isNotEmpty() }?.asText(), argMissing, execute)
    constructor(senderMessage: List<String> = listOf(), argMissing: Boolean = false, execute: () -> Unit = {})
            : this(senderMessage.takeIf { it.isNotEmpty() }?.asText(), argMissing, execute)
}

inline fun Executor<*>.exception(
        senderMessage: BaseComponent? = null,
        noinline execute: () -> Unit = {}
): Nothing = throw CommandException(senderMessage, execute = execute)

inline fun Executor<*>.exception(
        senderMessage: String = "",
        noinline execute: () -> Unit = {}
): Nothing = throw CommandException(senderMessage, execute = execute)

inline fun Executor<*>.exception(
        senderMessage: List<String> = listOf(),
        noinline execute: () -> Unit = {}
): Nothing = throw CommandException(senderMessage, execute = execute)

fun WithPlugin<*>.simpleCommand(
        name: String,
        vararg aliases: String = arrayOf(),
        description: String = "",
        block: ExecutorBlock
) = plugin.simpleCommand(name, *aliases, description = description, block = block)

fun Plugin.simpleCommand(
        name: String,
        vararg aliases: String = arrayOf(),
        description: String = "",
        block: ExecutorBlock
) = simpleCommand(name, *aliases, plugin = this, description = description, block = block)

fun simpleCommand(
        name: String,
        vararg aliases: String = arrayOf(),
        plugin: Plugin,
        description: String = "",
        block: ExecutorBlock
) = command(name, *aliases, plugin = plugin) {

    if (description.isNotBlank()) this.description = description

    executor(block)
}

inline fun WithPlugin<*>.command(
        name: String,
        vararg aliases: String = arrayOf(),
        block: CommandMaker
) = plugin.command(name, *aliases, block = block)

inline fun Plugin.command(
        name: String,
        vararg aliases: String = arrayOf(),
        block: CommandMaker
) = command(name, *aliases, plugin = this, block = block)

inline fun command(
        name: String,
        vararg aliases: String = arrayOf(),
        plugin: Plugin,
        block: CommandMaker
) = CommandDSL(name, *aliases).apply(block).apply {
    register(plugin)
}

fun <T : CommandSender> Executor<T>.argumentExecutorBuilder(
        posIndex: Int = 1,
        label: String
) = Executor(
        sender,
        this@argumentExecutorBuilder.label + " " + label,
        runCatching { args.sliceArray(posIndex..args.size) }.getOrDefault((emptyArray()))
)

inline fun TabCompleter.argumentCompleteBuilder(
        index: Int,
        block: (String) -> List<String>
): List<String> {
    if(args.size == index+1) {
        return block(args.getOrNull(index) ?: "")
    }
    return emptyList()
}

inline fun <T> Executor<*>.optional(block: () -> T): T? {
    try {
        return block()
    }catch (exception: CommandException) {
        if(exception.argMissing) return null
        else throw exception
    }
}

inline fun <reified T> Executor<*>.array(
        startIndex: Int,
        endIndex: Int,
        usageIndexPerArgument: Int = 1,
        block: (index: Int) -> T
): Array<T> {
    if (endIndex <= startIndex)
        throw IllegalArgumentException("endIndex can't be lower or equals a startIndex.")
    if(usageIndexPerArgument <= 0)
        throw IllegalArgumentException("usageIndexPerArgument can't be lower than 1.")

    val arguments = (endIndex - startIndex) / usageIndexPerArgument

    return Array(arguments) {
        block(startIndex + (it * usageIndexPerArgument))
    }
}

class Executor<E : CommandSender>(
        val sender: E,
        val label: String,
        val args: Array<out String>
)

class TabCompleter(
        val sender: CommandSender,
        val alias: String,
        val args: Array<out String>
)

open class CommandDSL(
        name: String,
        vararg aliases: String = arrayOf(),
        executor: ExecutorBlock? = null
) : org.bukkit.command.Command(name.trim()) {

    var onlyInGameMessage = ""

    init { this.aliases = aliases.toList() }

    private var executor: ExecutorBlock? = executor
    private var tabCompleter: TabCompleterBlock? = null

    private val executors: MutableMap<KClass<out CommandSender>, Executor<CommandSender>.() -> Unit> = mutableMapOf()

    val subCommands: MutableList<CommandDSL> = mutableListOf()

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        if (!permission.isNullOrBlank() && !sender.hasPermission(permission)) {
            sender.sendMessage(permissionMessage)
        } else {
            if (subCommands.isNotEmpty()) {
                val subCommand = args.getOrNull(0)?.let { arg ->
                    subCommands.find {
                        it.name.equals(arg, true) ||
                                it.aliases.any { it.equals(arg, true) }
                    }
                }
                if (subCommand != null) {
                    subCommand.execute(sender, "$label ${args.get(0)}", args.sliceArray(1 until args.size))
                    return true
                }
            }
            try {
                val genericExecutor = executors.getByInstance(sender::class)
                if (genericExecutor != null) {
                    genericExecutor.invoke(Executor(sender, label, args))
                } else {
                    val hasPlayer = executors.getByInstance(Player::class)
                    if (hasPlayer != null) {
                        if (executor != null) {
                            executor?.invoke(Executor(sender, label, args))
                        } else sender.sendMessage(onlyInGameMessage)
                    } else {
                        executor?.invoke(Executor(sender, label, args))
                    }
                }
            } catch (ex: CommandException) {
                ex.senderMessage?.also { sender.sendMessage(it) }
                ex.execute()
            }
        }
        return true
    }

    override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): List<String> {
        return if (tabCompleter != null) {
            tabCompleter!!.invoke(TabCompleter(sender, alias, args))
        } else {
            defaultTabComplete(sender, alias, args)
        }
    }

    open fun defaultTabComplete(sender: CommandSender, alias: String, args: Array<out String>): List<String> {
        if (args.size > 1) {
            val subCommand = subCommands.find { it.name.equals(args.getOrNull(0), true) }
            if (subCommand != null) {
                return subCommand.tabComplete(sender, args.get(0), args.sliceArray(1 until args.size))
            } else {
                emptyList<String>()
            }
        } else if (args.size > 0) {
            if (subCommands.isNotEmpty()) {
                return subCommands
                        .filter { it.name.startsWith(args.get(0), true) }
                        .map { it.name }
            } else return super.tabComplete(sender, alias, args)
        }
        return super.tabComplete(sender, alias, args)
    }

    fun TabCompleter.default() = defaultTabComplete(sender, alias, args)

    open fun subCommandBuilder(name: String, vararg aliases: String = arrayOf()): CommandDSL {
        return CommandDSL(name, *aliases).also {
            it.permission = this.permission
            it.permissionMessage = this.permissionMessage
            it.onlyInGameMessage = this.onlyInGameMessage
            it.usageMessage = this.usageMessage
        }
    }

    inline fun command(
            name: String,
            vararg aliases: String = arrayOf(),
            block: CommandMaker
    ): CommandDSL {
        return subCommandBuilder(name, *aliases).apply(block).also { subCommands.add(it) }
    }

    open fun executor(block: ExecutorBlock) {
        executor = block
    }

    open fun executorPlayer(block: ExecutorPlayerBlock) {
        genericExecutor(Player::class, block)
    }

    open fun tabComplete(block: TabCompleterBlock) {
        tabCompleter = block
    }

    open fun <T : CommandSender> genericExecutor(clazz: KClass<T>, block: Executor<T>.() -> Unit) {
        executors.put(clazz, block as Executor<CommandSender>.() -> Unit)
    }

    inline fun <reified T : CommandSender> genericExecutor(noinline block: Executor<T>.() -> Unit) {
        genericExecutor(T::class, block)
    }

    private fun <T> MutableMap<KClass<out CommandSender>, T>.getByInstance(clazz: KClass<*>): T? {
        return entries.find { clazz.isSubclassOf(it.key) }?.value
    }

}