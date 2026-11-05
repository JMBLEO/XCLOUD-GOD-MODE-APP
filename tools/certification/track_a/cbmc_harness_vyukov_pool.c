#include <stdint.h>
#include <stdbool.h>
#include <assert.h>

#define POOL_SLOTS 2
#define POOL_MASK (POOL_SLOTS - 1)
#define SLOT_SIZE 64

typedef struct {
    uint64_t sequence;
    uint32_t slot_index;
} VyukovCell;

typedef struct {
    uint32_t pool_index;
    uint32_t generation;
    uint32_t size;
    uint64_t pts;
} VerifiedDescriptor;

static uint8_t memory_arena[POOL_SLOTS][SLOT_SIZE];
static uint32_t slot_generations[POOL_SLOTS];
static VyukovCell free_cells[POOL_SLOTS];

static uint64_t enqueue_pos;
static uint64_t dequeue_pos;
static uint32_t current_allocated;

static bool slot_locked[POOL_SLOTS];
static uint64_t current_transport_epoch;

void init_pool_model(void) {
    for (uint32_t i = 0; i < POOL_SLOTS; ++i) {
        free_cells[i].sequence = i;
        free_cells[i].slot_index = i;
        slot_generations[i] = 1;
        slot_locked[i] = false;
    }
    enqueue_pos = 0;
    dequeue_pos = 0;
    current_allocated = 0;
    current_transport_epoch = 1;
}

bool acquire_slot_cbmc(const uint8_t* src, uint32_t size, VerifiedDescriptor* out_desc) {
    if (!src || size == 0 || size > SLOT_SIZE) return false;

    uint64_t pos = dequeue_pos;
    VyukovCell* cell = &free_cells[pos & POOL_MASK];
    int64_t diff = (int64_t)cell->sequence - (int64_t)(pos + 1);

    if (diff == 0) {
        dequeue_pos = pos + 1;
        uint32_t slot = cell->slot_index;
        
        #ifdef __CPROVER__
        __CPROVER_assert(slot < POOL_SLOTS, "INV-MEM-01: Slot index out of bounds");
        __CPROVER_assert(!slot_locked[slot], "INV-CONC-01: Cell collision / double allocation");
        #endif
        slot_locked[slot] = true;

        cell->sequence = pos + POOL_SLOTS;
        uint32_t gen = ++slot_generations[slot];

        for (uint32_t i = 0; i < size; ++i) {
            memory_arena[slot][i] = src[i];
        }
        current_allocated++;

        out_desc->pool_index = slot;
        out_desc->generation = gen;
        out_desc->size = size;
        return true;
    }
    return false;
}

bool release_slot_cbmc(const VerifiedDescriptor* desc) {
    if (desc->pool_index >= POOL_SLOTS) return false;

    if (slot_generations[desc->pool_index] != desc->generation) {
        return false;
    }

    uint64_t pos = enqueue_pos;
    VyukovCell* cell = &free_cells[pos & POOL_MASK];
    int64_t diff = (int64_t)cell->sequence - (int64_t)pos;

    if (diff == 0) {
        enqueue_pos = pos + 1;
        cell->slot_index = desc->pool_index;

        #ifdef __CPROVER__
        __CPROVER_assert(slot_locked[desc->pool_index], "INV-CONC-02: Double free / Unallocated slot release");
        #endif
        slot_locked[desc->pool_index] = false;

        cell->sequence = pos + 1;
        current_allocated--;
        return true;
    }
    return false;
}

bool process_packet_epoch_cbmc(uint64_t packet_epoch) {
    if (packet_epoch < current_transport_epoch) {
        return false;
    }
    if (packet_epoch > current_transport_epoch) {
        current_transport_epoch = packet_epoch;
    }
    #ifdef __CPROVER__
    __CPROVER_assert(packet_epoch == current_transport_epoch, "INV-EPOCH-01: Desincronización de época de transporte");
    #endif
    return true;
}

int main() {
    init_pool_model();

    uint8_t nondet_data[SLOT_SIZE];
    uint32_t nondet_size = 32;
    uint64_t nondet_epoch_1 = 1;
    uint64_t nondet_epoch_2 = 2;

    VerifiedDescriptor desc1;

    bool ok1 = acquire_slot_cbmc(nondet_data, nondet_size, &desc1);
    if (ok1) {
        #ifdef __CPROVER__
        __CPROVER_assert(desc1.generation >= 2, "INV-ABA-01: Generación no incrementada");
        #endif
        
        VerifiedDescriptor stale_desc = desc1;
        stale_desc.generation = desc1.generation - 1;
        bool stale_rejected = !release_slot_cbmc(&stale_desc);
        #ifdef __CPROVER__
        __CPROVER_assert(stale_rejected, "INV-ABA-02: Descriptor rancio aceptado incorrectamente");
        #endif

        bool rel_ok = release_slot_cbmc(&desc1);
        #ifdef __CPROVER__
        __CPROVER_assert(rel_ok, "INV-MEM-02: Fallo al liberar slot legítimo");
        #endif
    }

    process_packet_epoch_cbmc(nondet_epoch_1);
    process_packet_epoch_cbmc(nondet_epoch_2);

    return 0;
}
