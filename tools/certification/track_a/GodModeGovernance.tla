------------------------- MODULE GodModeGovernance -------------------------
EXTENDS Integers, Sequences, FiniteSets, TLC

CONSTANTS 
    MaxRecoveriesBudget,    \* Límite horario de recuperaciones (ej. 3)
    MaxMigrationsBudget     \* Límite horario de migraciones (ej. 2)

VARIABLES 
    fsmState,               \* Estado FSM de 11 estados
    activeEngine,           \* Motor gráfico asignado
    recoveryCount,          \* Cuota consumida de recuperación
    migrationCount,         \* Cuota consumida de migración
    sessionAlive,           \* Estado lógico del Control Plane
    safetyEngaged           \* Flag de FailSafe activo

vars == <<fsmState, activeEngine, recoveryCount, migrationCount, sessionAlive, safetyEngaged>>

AllStates == {
    "Idle", "Initializing", "Buffering", "Streaming", "Degraded",
    "RecoveringL1", "RecoveringL2", "RecoveringL3", "Suspended", "Failed", "Shutdown"
}

AllEngines == {"Motor1", "Motor2", "Motor3", "Motor4", "Motor5", "Motor6"}

TypeOK ==
    /\ fsmState \in AllStates
    /\ activeEngine \in AllEngines
    /\ recoveryCount \in 0..(MaxRecoveriesBudget + 1)
    /\ migrationCount \in 0..(MaxMigrationsBudget + 1)
    /\ sessionAlive \in {TRUE, FALSE}
    /\ safetyEngaged \in {TRUE, FALSE}

\* -------------------------------------------------------------------------
\* INVARIANTES MATEMÁTICAS FORMALES
\* -------------------------------------------------------------------------

\* INV-001: El Control Plane jamás muere por fallos internos del Media Plane
INV1_ControlPlaneIsolation ==
    (fsmState \in {"RecoveringL1", "RecoveringL2", "RecoveringL3", "Degraded"}) => sessionAlive

\* INV-003: Bloqueo estricto en Motor 4 (HWC2 Directo) cuando FailSafe se dispara
INV3_FailSafeEngineLock ==
    safetyEngaged => (activeEngine = "Motor4")

\* INV-005: El Safety Envelope confina el sistema a FailSafe si se agotan cuotas
INV5_SafetyEnvelopeExhaustion ==
    ((recoveryCount >= MaxRecoveriesBudget) \/ (migrationCount >= MaxMigrationsBudget)) => safetyEngaged

\* -------------------------------------------------------------------------
\* CONDICIONES INICIALES
\* -------------------------------------------------------------------------
Init ==
    /\ fsmState = "Idle"
    /\ activeEngine = "Motor3"  \* AMediaCodec + Vulkan Compute AI por defecto
    /\ recoveryCount = 0
    /\ migrationCount = 0
    /\ sessionAlive = TRUE
    /\ safetyEngaged = FALSE

\* -------------------------------------------------------------------------
\* ACCIONES Y TRANSICIONES
\* -------------------------------------------------------------------------

BootstrapSession ==
    /\ fsmState = "Idle"
    /\ fsmState' = "Initializing"
    /\ UNCHANGED <<activeEngine, recoveryCount, migrationCount, sessionAlive, safetyEngaged>>

ConnectStream ==
    /\ fsmState = "Initializing"
    /\ fsmState' = "Buffering"
    /\ UNCHANGED <<activeEngine, recoveryCount, migrationCount, sessionAlive, safetyEngaged>>

EnterStreaming ==
    /\ fsmState = "Buffering"
    /\ fsmState' = "Streaming"
    /\ UNCHANGED <<activeEngine, recoveryCount, migrationCount, sessionAlive, safetyEngaged>>

TriggerRecoverL2 ==
    /\ fsmState = "Streaming"
    /\ ~safetyEngaged
    /\ recoveryCount < MaxRecoveriesBudget
    /\ fsmState' = "RecoveringL2"
    /\ recoveryCount' = recoveryCount + 1
    /\ UNCHANGED <<activeEngine, migrationCount, sessionAlive, safetyEngaged>>

ResolveRecoverL2 ==
    /\ fsmState = "RecoveringL2"
    /\ fsmState' = "Streaming"
    /\ UNCHANGED <<activeEngine, recoveryCount, migrationCount, sessionAlive, safetyEngaged>>

TriggerThermalMigration ==
    /\ fsmState = "Streaming"
    /\ ~safetyEngaged
    /\ migrationCount < MaxMigrationsBudget
    /\ activeEngine' \in AllEngines \ {activeEngine}
    /\ migrationCount' = migrationCount + 1
    /\ fsmState' = "Buffering"
    /\ UNCHANGED <<recoveryCount, sessionAlive, safetyEngaged>>

TripSafetyEnvelope ==
    /\ ~safetyEngaged
    /\ (recoveryCount >= MaxRecoveriesBudget \/ migrationCount >= MaxMigrationsBudget)
    /\ safetyEngaged' = TRUE
    /\ activeEngine' = "Motor4"
    /\ fsmState' = "Degraded"
    /\ UNCHANGED <<recoveryCount, migrationCount, sessionAlive>>

StreamUnderFailSafe ==
    /\ safetyEngaged
    /\ fsmState = "Degraded"
    /\ fsmState' = "Streaming"
    /\ UNCHANGED <<activeEngine, recoveryCount, migrationCount, sessionAlive, safetyEngaged>>

GracefulShutdown ==
    /\ fsmState \in {"Streaming", "Degraded", "Suspended"}
    /\ fsmState' = "Shutdown"
    /\ sessionAlive' = FALSE
    /\ UNCHANGED <<activeEngine, recoveryCount, migrationCount, safetyEngaged>>

Next ==
    \/ BootstrapSession
    \/ ConnectStream
    \/ EnterStreaming
    \/ TriggerRecoverL2
    \/ ResolveRecoverL2
    \/ TriggerThermalMigration
    \/ TripSafetyEnvelope
    \/ StreamUnderFailSafe
    \/ GracefulShutdown

\* -------------------------------------------------------------------------
\* PROPIEDADES DE LIVENESS (CONVERGENCIA SIN DEADLOCKS NI LIVELOCKS)
\* -------------------------------------------------------------------------
Fairness == 
    /\ WF_vars(Next)
    /\ SF_vars(ResolveRecoverL2)
    /\ SF_vars(TripSafetyEnvelope)
    /\ SF_vars(StreamUnderFailSafe)

LivenessConvergence ==
    /\ <>(fsmState \in {"Streaming", "Shutdown"})
    /\ [](~safetyEngaged \/ <>(fsmState = "Streaming" /\ activeEngine = "Motor4"))

Spec == Init /\ [][Next]_vars /\ Fairness
=============================================================================
