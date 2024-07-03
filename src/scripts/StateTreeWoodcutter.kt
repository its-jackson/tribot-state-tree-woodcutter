package scripts

import org.tribot.script.sdk.*
import org.tribot.script.sdk.Combat.*
import org.tribot.script.sdk.Waiting.wait
import org.tribot.script.sdk.antiban.Antiban
import org.tribot.script.sdk.frameworks.behaviortree.*
import org.tribot.script.sdk.painting.Painting
import org.tribot.script.sdk.script.TribotScript
import org.tribot.script.sdk.script.TribotScriptManifest
import scripts.nexus.sdk.control
import scripts.nexus.sdk.mouse.MousePaintThread
import scripts.nexus.sdk.routine.api.Logger
import scripts.nexus.sdk.routine.api.managers.AntibanManager
import scripts.nexus.sdk.routine.api.managers.BankManager
import scripts.nexus.sdk.routine.api.managers.TribotDefaultGameSettingManager
import scripts.nexus.sdk.routine.api.woodcutting.Axe
import scripts.nexus.sdk.routine.api.woodcutting.TreeManager
import scripts.nexus.sdk.routine.api.woodcutting.TreeType
import scripts.nexus.sdk.statetree.*
import java.awt.Color
import java.lang.System.currentTimeMillis
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.jvm.optionals.getOrNull

private const val KEY_CURRENT_TREE = "currentTree"
private const val KEY_NEXT_TREE = "nextTree"
private const val KEY_SHOULD_HOVER_NEXT_TREE = "shouldHoverNextTree"
private const val KEY_SHOULD_HOVER_SPEC_ORB = "shouldHoverSpecOrb"

data class AIConfig(
    val axe: Axe = Axe.DRAGON,
    val tree: TreeType = TreeType.MAGIC_TREE_AT_WOODCUTTING_GUILD
)

class AIContextProvider(
    var config: AIConfig,
    var logger: Logger,
    var treeManager: TreeManager,
    var bankManager: BankManager,
    var antibanManager: AntibanManager,
    var gameSettingManager: TribotDefaultGameSettingManager,
    var localTime: LocalTime = getCurrentTime(),
    var lastBehaviorUpdateTime: LocalTime = localTime,
    var lastColorUpdateHour: Int = localTime.hour,
    var currentUIColor: Color = Color.WHITE,
    var idleTickCounter: Int = 0,
    var treesChoppedCounter: Int = 0,
    var logsChoppedCounter: Int = 0,
    var initialLogCount: Int = 0,
    var finalLogCount: Int = 0,
) : IContextProvider

@TribotScriptManifest(
    name = "iChopper",
    author = "Polymorphic",
    category = "Woodcutting",
    description = "Local"
)
class StateTreeWoodcutter : TribotScript {
    private val paintThread = MousePaintThread()
    private var stateTree: StateTree<AIContextProvider>? = null
    private var lastUpdateTimeMs: Long = currentTimeMillis()
    private val defaultWaitTime: Int = 25
    private var waitTime: Int = defaultWaitTime

    override fun execute(p0: String) {
        val ctxData = AIContextProvider(
            config = AIConfig(),
            logger = Logger("Woodcutter AI"),
            antibanManager = AntibanManager(),
            gameSettingManager = TribotDefaultGameSettingManager(),
            bankManager = BankManager(BankManager.BankType.DEPOSIT_BOX),
            treeManager = TreeManager()
        ).let {
            ContextData(it)
        }

        val parameters = StateTreeParameters().apply {
            this[KEY_CURRENT_TREE] = null
            this[KEY_NEXT_TREE] = null
            this[KEY_SHOULD_HOVER_NEXT_TREE] = false
            this[KEY_SHOULD_HOVER_SPEC_ORB] = false
        }

        val globalTasks = listOf(
            buildTask<AIContextProvider>(30) {
                behaviorTree {
                    sequence {
                        perform {
                            val currentTime = getCurrentTime()
                            val currentHour = currentTime.hour
                            val context = it.data.context
                            if (currentHour != context.lastColorUpdateHour) {
                                context.currentUIColor = getColorForHour(currentHour)
                                context.lastColorUpdateHour = currentHour
                                context.logger.info("Color updated for hour: $currentHour")
                            }
                            if (Duration.between(context.lastBehaviorUpdateTime, currentTime).toHours() >= 1) {
                                if (currentHour in 6..18) {
                                    context.logger.info("It's day time. Adjusting behavior accordingly")
                                } else {
                                    context.logger.info("It's night time. Adjusting behavior accordingly")
                                }
                                context.lastBehaviorUpdateTime = currentTime
                            }
                        }
                    }
                }
            }
        )

        val evaluators = listOf(
            buildEvaluator<AIContextProvider>(
                onStartFunc = {
                    val context = it.data.context
                    context.treeManager.tree = context.config.tree.tree

                    paintThread.start()
                    Antiban.setScriptAiAntibanEnabled(false)

                    val currentTime = context.localTime
                    val currentHour = currentTime.hour
                    context.currentUIColor = getColorForHour(currentHour)
                    context.lastColorUpdateHour = currentHour
                    context.logger.info("Evaluator onStart color updated for hour: $currentHour")

                    Painting.addPaint { g2 ->
                        g2.color = context.currentUIColor
                        g2.drawString("iChopper", 15, 60)
                        g2.drawString("State tree: ${stateTree?.currentStateName}", 15, 80)
                        g2.drawString("Local clock: ${it.data.context.localTime.let { getTimeFormatted(it) }}", 15, 100)
                        g2.drawString("Trees chopped: ${it.data.context.treesChoppedCounter}", 15, 120)
                        g2.drawString("Logs chopped: ${it.data.context.logsChoppedCounter}", 15, 140)
                    }
                },
                onTickFunc = {
                    it.data.context.localTime = getCurrentTime()
                },
                onStopFunc = {
                    it.data.context.localTime = getCurrentTime()
                }
            )
        )

        val rootState = buildRootState()
        stateTree = buildStateTree(
            rootState,
            parameters,
            ctxData,
            globalTasks,
            evaluators
        )

        while (true) {
            wait(waitTime)

            val currentTimeMs = currentTimeMillis()
            val deltaTimeMs = currentTimeMs - lastUpdateTimeMs
            val fps = if (deltaTimeMs > 0) 1000 / deltaTimeMs else 0
            when (val tickStatus = stateTree?.tick(deltaTimeMs, fps)) {
                null -> {
                    break
                }

                BehaviorTreeStatus.SUCCESS -> {
                    //
                }

                BehaviorTreeStatus.FAILURE -> {
                    //
                }

                BehaviorTreeStatus.KILL -> {
                    stateTree?.stop()
                    break
                }
            }

            lastUpdateTimeMs = currentTimeMs
        }
    }
}

fun buildRootState() = buildSelectorState(
    name = "RootSelectorState",
    childStates = listOf(
        // Ensure player is logged in -> goto root
        buildLoginGameState(),
        // Banking state (inventory is full) -> goto root
        buildBankingState(),
        // Move to the area to check for trees if not in area -> goto root
        buildCheckingAreaState(),
        // Move to area, select and validate optimal tree to chop down if in area and no tree current tree -> goto root
        buildVerifyingTreeState(),
        // Move to tree state -> goto root
        buildMovingToTreeState(),
        // Use special attack state -> goto root
        buildUseSpecialAttackState(),
        // Is chopping tree state -> goto banking state, special attack state, verifying tree state, root
        buildChoppingTreeState(),
        // Chop down the tree state -> goto chopping state, root
        buildChopDownTreeState()
    )
)

fun buildLoginGameState(
    transitions: List<Transition<AIContextProvider>> = listOf(
        Transition(null) { Login.isLoggedIn() }
    )
) = buildState(
    name = "LoginGameState",
    enterConditions = listOf { !Login.isLoggedIn() },
    tasks = listOf(buildLoginGameTask()),
    transitions = transitions
)

fun buildLoginGameTask() = buildTask<AIContextProvider> {
    behaviorTree {
        sequence {
            control {
                condition {
                    Login.login()
                }
            }
        }
    }
}

fun buildMovingToBankState() = buildState(
    name = "MovingToBankState",
    tasks = listOf(buildMoveToBankTask()),
    enterConditions = listOf {
        !it.data.context.treeManager.isNearBank()
    },
    transitions = listOf(
        Transition(null) {
            it.data.context.treeManager.isNearBank()
        }
    )
)

fun buildMoveToBankTask() = buildTask<AIContextProvider> {
    behaviorTree {
        selector {
            condition {
                MyPlayer.isMoving()
            }
            condition {
                it.data.context.treeManager.moveToBank()
            }
        }
    }
}

fun buildBankingState() = buildState(
    name = "BankingState",
    tasks = listOf(buildBankLogsTask()),
    childStates = listOf(buildMovingToBankState()),
    enterConditions = listOf {
        it.data.context.treeManager.shouldClearInv()
    },
    transitions = listOf(
        Transition(null) {
            !it.data.context.treeManager.shouldClearInv()
        },
    )
)

fun buildBankLogsTask() = buildTask<AIContextProvider> {
    behaviorTree {
        sequence {
            condition {
                it.data.context.bankManager.open()
            }
            condition {
                it.data.context.bankManager.depositInventory()
            }
            perform {
                it.data.context.bankManager.close()
            }
        }
    }
}

fun buildCheckingAreaState() = buildState(
    name = "CheckingAreaState",
    tasks = listOf(buildCheckAreaTask()),
    enterConditions = listOf {
        !it.data.context.treeManager.isInCurrentArea()
    },
    transitions = listOf(
        Transition(null) {
            it.data.context.treeManager.isInCurrentArea()
        }
    )
)

fun buildCheckAreaTask() = buildTask<AIContextProvider> {
    behaviorTree {
        selector {
            condition {
                MyPlayer.isMoving()
            }
            condition {
                it.data.context.treeManager.moveToCurrentArea()
            }
        }
    }
}

fun buildVerifyingTreeState() = buildState(
    name = "VerifyingTreeState",
    tasks = listOf(buildVerifyTreeTask()),
    enterConditions = listOf {
        it.parameters[KEY_CURRENT_TREE] === null
    },
    transitions = listOf(
        Transition(null) {
            it.parameters[KEY_CURRENT_TREE] !== null
        }
    )
)

fun buildVerifyTreeTask() = buildTask<AIContextProvider> {
    behaviorTree {
        sequence {
            perform {
                it.parameters[KEY_CURRENT_TREE] = it.data.context.treeManager.getBestInteractableTreeObject()
            }
        }
    }
}

fun buildMovingToTreeState() = buildState(
    name = "MovingToTreeState",
    enterConditions = listOf {
        it.parameters.getGameObject(KEY_CURRENT_TREE)?.let { obj -> obj.distance() > 7 } ?: false
    },
    tasks = listOf(buildMoveToTreeTask()),
    transitions = listOf(
        Transition(null) {
            it.parameters.getGameObject(KEY_CURRENT_TREE)?.let { obj -> obj.distance() <= 7 } ?: false
        },
        Transition(null) {
            it.parameters.getGameObject(KEY_CURRENT_TREE) === null
        }
    )
)

fun buildMoveToTreeTask() = buildTask<AIContextProvider> {
    behaviorTree {
        sequence {
            selector {
                condition {
                    it.parameters.getGameObject(KEY_CURRENT_TREE)?.let { tree ->
                        it.data.context.treeManager.isTreeValid(tree)
                    }
                }
                sequence {
                    perform {
                        it.data.context.logger.info("Identified tree died")
                    }
                    perform {
                        it.parameters[KEY_CURRENT_TREE] = null
                    }
                }
            }

            selector {
                condition {
                    MyPlayer.isMoving()
                }
                condition {
                    it.parameters.getGameObject(KEY_CURRENT_TREE)?.let { tree ->
                        it.data.context.treeManager.moveToTree(tree) {
                            it.parameters.getGameObject(KEY_CURRENT_TREE)?.let { tree ->
                                !it.data.context.treeManager.isTreeValid(tree)
                            } ?: false
                        }
                    }
                }
            }
        }
    }
}

fun buildChoppingTreeState() = buildState(
    name = "ChoppingTreeState",
    tasks = listOf(buildChoppingTreeTask(), buildAntibanTask()),
    enterConditions = listOf {
        it.data.context.config.axe.matchesPlayerAnimation()
                || it.parameters.getGameObject(KEY_CURRENT_TREE)?.let { tree ->
                    it.data.context.treeManager.isTreeValid(tree)
                    && MyPlayer.get().getOrNull()?.isInteractingWithObject(tree) == true
                } ?: false
    },
    transitions = listOf(
        Transition(null) {
            it.parameters.getGameObject(KEY_CURRENT_TREE) === null
        },
        Transition(null) {
            !it.data.context.config.axe.matchesPlayerAnimation()
                    && !it.data.context.config.axe.matchesSpecialAttackAnimation()
                    && it.data.context.idleTickCounter >= 4 // Check for idle time
        },
        Transition(buildBankingState()) {
            it.data.context.treeManager.shouldClearInv()
        },
        Transition(buildUseSpecialAttackState()) {
            shouldUseSpecialAttack()
        }
    ),
    onEnterFunc = {
        val params = it.parameters
        val context = it.data.context
        val logger = context.logger

        context.idleTickCounter = 0
        context.initialLogCount = it.data.context.treeManager.getLogCount()
        logger.info("onEnter initial log count: ${context.initialLogCount}")

        val nextTree = params.getGameObject(KEY_NEXT_TREE)
        if (nextTree === null || !context.treeManager.isTreeValid(nextTree)) {
            val currentTree = params.getGameObject(KEY_CURRENT_TREE)
            context.treeManager.findNextTreeToHover(currentTree)?.let { tree ->
                logger.info("Found next tree during onEnter -> $tree")
                params[KEY_NEXT_TREE] = tree
            }
        }
    },
    onExitFunc = {
        val params = it.parameters
        val context = it.data.context
        val treeManager = context.treeManager
        val logger = context.logger

        context.idleTickCounter = 0

        context.finalLogCount = treeManager.getLogCount()
        val logsChopped = (context.finalLogCount - context.initialLogCount).coerceAtLeast(0)
        context.logsChoppedCounter += logsChopped

        logger.info("onExit final log count: ${it.data.context.finalLogCount}")
        logger.info("onExit logs chopped: $logsChopped")

        handleTree(params, KEY_CURRENT_TREE, treeManager, logger)
        handleTree(params, KEY_NEXT_TREE, treeManager, logger)
        assignNextTreeToCurrentTree(params, treeManager, logger)
    }
)

private fun handleTree(
    params: StateTreeParameters,
    key: String,
    treeManager: TreeManager,
    logger: Logger
) {
    val tree = params.getGameObject(key)
    if (tree !== null && !treeManager.isTreeValid(tree)) {
        logger.info("$key chopped down during onExit")
        params[key] = null
    }
}

private fun assignNextTreeToCurrentTree(
    params: StateTreeParameters,
    treeManager: TreeManager,
    logger: Logger
) {
    val currentTree = params.getGameObject(KEY_CURRENT_TREE)
    val nextTree = params.getGameObject(KEY_NEXT_TREE)

    if (nextTree !== null && currentTree === null && treeManager.isTreeValid(nextTree)) {
        logger.info("Assign nextTree to currentTree during onExit")
        params[KEY_CURRENT_TREE] = nextTree
        params[KEY_NEXT_TREE] = null
    }
}

fun buildChoppingTreeTask() = buildTask<AIContextProvider> {
    behaviorTree {
        sequence {
            // Check and update if current tree chopped down
            selector {
                condition {
                    it.parameters.getGameObject(KEY_CURRENT_TREE)?.let { tree ->
                        it.data.context.treeManager.isTreeValid(tree)
                    }
                }
                sequence {
                    perform {
                        it.data.context.logger.info("Tree chopped down during chopping task...")
                    }
                    perform {
                        it.data.context.treesChoppedCounter++
                    }
                    perform {
                        it.parameters[KEY_CURRENT_TREE] = null
                    }
                }
            }

            // Check and update if next tree chopped down
            selector {
                condition {
                    it.parameters.getGameObject(KEY_NEXT_TREE)?.let { tree ->
                        it.data.context.treeManager.isTreeValid(tree)
                    }
                }
                sequence {
                    perform {
                        it.parameters[KEY_NEXT_TREE] = null
                    }
                    perform {
                        val currentTree = it.parameters.getGameObject(KEY_CURRENT_TREE)
                        it.data.context.treeManager.findNextTreeToHover(currentTree)?.let { tree ->
                            it.data.context.logger.info("Found next tree during chopping task -> $tree")
                            it.parameters[KEY_NEXT_TREE] = tree
                        }
                    }
                }
            }

            // Hover the spec orb or the next tree
            selector {
                sequence {
                    condition {
                        shouldHoverSpecialAttackOrb()
                    }
                    perform {
                        hoverSpecialAttackOrb()
                    }
                }
                sequence {
                    condition {
                        it.parameters.getGameObject(KEY_NEXT_TREE)?.let { tree ->
                            it.data.context.treeManager.isTreeValid(tree)
                        }
                    }
                    perform {
                        it.parameters.getGameObject(KEY_NEXT_TREE)?.let { tree ->
                            tree.hover()
                        }
                    }
                }
            }

            // Increment or reset the idle tick counter to ensure transition when not chopping
            perform {
                if (it.data.context.config.axe.matchesPlayerAnimation()
                    || it.data.context.config.axe.matchesSpecialAttackAnimation()
                ) {
                    it.data.context.idleTickCounter = 0
                } else {
                    it.data.context.idleTickCounter++
                }
            }
        }
    }
}

fun buildChopDownTreeState() = buildState(
    name = "ChopDownTreeState",
    tasks = listOf(buildChopDownTreeTask()),
    enterConditions = listOf {
        !it.data.context.config.axe.matchesPlayerAnimation()
                || it.parameters.getGameObject(KEY_CURRENT_TREE)?.let { tree ->
                    it.data.context.treeManager.isTreeValid(tree)
                    && MyPlayer.get().getOrNull()?.isInteractingWithObject(tree) == false
                } ?: false
    },
    transitions = listOf(
        Transition(buildChoppingTreeState()) {
            it.data.context.config.axe.matchesPlayerAnimation()
                    || it.parameters.getGameObject(KEY_CURRENT_TREE)?.let { tree ->
                        it.data.context.treeManager.isTreeValid(tree)
                        && MyPlayer.get().getOrNull()?.isInteractingWithObject(tree) == true
                    } ?: false
        },
        Transition(null) {
            it.parameters.getGameObject(KEY_CURRENT_TREE) === null
        }
    )
)

fun buildChopDownTreeTask() = buildTask<AIContextProvider> {
    behaviorTree {
        sequence {
            selector {
                condition {
                    it.parameters.getGameObject(KEY_CURRENT_TREE)?.let { tree ->
                        it.data.context.treeManager.isTreeValid(tree)
                    }
                }
                sequence {
                    perform {
                        it.data.context.logger.info("Current tree chopped down...")
                    }
                    perform {
                        it.parameters[KEY_CURRENT_TREE] = it.parameters[KEY_NEXT_TREE]
                    }
                }
            }

            selector {
                condition {
                    MyPlayer.isMoving() && it.parameters.getGameObject(KEY_CURRENT_TREE)?.let { tree ->
                        it.data.context.treeManager.isTreeValid(tree)
                    } ?: false
                }
                perform {
                    it.parameters.getGameObject(KEY_CURRENT_TREE)?.let { tree ->
                        it.data.context.treeManager.interactTreeObject(tree, it.data.context.config.axe) {
                            it.parameters.getGameObject(KEY_CURRENT_TREE)?.let { tree ->
                                !it.data.context.treeManager.isTreeValid(tree)
                            } ?: false
                        }
                    }
                }
            }
        }
    }
}

fun buildUseSpecialAttackState() = buildState(
    name = "UseSpecialAttackState",
    tasks = listOf(buildUseSpecialAttackTask()),
    enterConditions = listOf {
        shouldUseSpecialAttack()
    },
    transitions = listOf(
        Transition(null) {
            !shouldUseSpecialAttack()
        }
    )
)

fun buildUseSpecialAttackTask() = buildTask<AIContextProvider> {
    behaviorTree {
        sequence {
            condition {
                useSpecialAttack()
            }
        }
    }
}

fun buildAntibanTask() = buildTask<AIContextProvider> {
    behaviorTree {
        sequence {
            perform {
                it.data.context.gameSettingManager.checkAllCameraTasks()
            }
            perform {
                it.data.context.antibanManager.executeSkillCheckTask(Skill.WOODCUTTING)
            }
            perform {
                it.data.context.antibanManager.executeEntityHoverTask()
            }
            perform {
                it.data.context.antibanManager.executeRandomMouseMoveTask()
            }
        }
    }
}

private const val SPECIAL_ATTACK_ORB_ROOT_IDX = 160

private val useSpecialAttackOrbAddress: WidgetAddress = WidgetAddress.create(
    SPECIAL_ATTACK_ORB_ROOT_IDX
) {
    it.actions.any { a ->
        a.equals("Use", ignoreCase = true)
    }
}

private fun shouldUseSpecialAttack(): Boolean {
    return getWeaponType() == WeaponType.AXE
            && canUseSpecialAttack()
            && !isSpecialAttackEnabled()
}

private fun useSpecialAttack(): Boolean {
    return activateSpecialAttack()
}

private fun shouldHoverSpecialAttackOrb(): Boolean {
    return getSpecialAttackPercent() in 80..90
}

private fun hoverSpecialAttackOrb(): Boolean {
    return useSpecialAttackOrbAddress.lookup()
        .getOrNull()
        ?.let { it.isVisible && it.hover() }
        ?: false
}

private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun getCurrentTime(): LocalTime {
    return LocalTime.now()
}

private fun getTimeFormatted(currentTime: LocalTime): String {
    return currentTime.format(formatter)
}

private fun getColorForHour(hour: Int): Color {
    return when (hour) {
        in 0..5 -> {
            val fraction = hour / 6.0
            interpolateColor(Color(0, 0, 139), Color.WHITE, fraction)
        }
        in 6..17 -> {
            Color.WHITE
        }
        in 18..23 -> {
            val fraction = (hour - 18) / 6.0
            interpolateColor(Color.WHITE, Color(0, 0, 139), fraction)
        }
        else -> {
            Color.WHITE
        }
    }
}

private fun interpolateColor(
    color1: Color,
    color2: Color,
    fraction: Double
): Color {
    val red = (color1.red + fraction * (color2.red - color1.red)).toInt()
    val green = (color1.green + fraction * (color2.green - color1.green)).toInt()
    val blue = (color1.blue + fraction * (color2.blue - color1.blue)).toInt()
    return Color(red, green, blue)
}