#!/usr/bin/env python3
# builds the report figures from bench_results.csv (whatever the harness dumped between
# ===CSV_BEGIN===/===CSV_END===). nothing here fabricates numbers.
import sys
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker
import pandas as pd

for f in ("Arial", "Helvetica", "DejaVu Sans"):
    if f in {x.name for x in matplotlib.font_manager.fontManager.ttflist}:
        matplotlib.rcParams["font.family"] = f
        break
matplotlib.rcParams["figure.dpi"] = 140

CSV = sys.argv[1] if len(sys.argv) > 1 else "bench_results.csv"
df = pd.read_csv(CSV)

NUMQ_P1 = 8
THEORY = (5.0 / 6.0) * NUMQ_P1

BLUE, GREEN, RED, ORANGE, PURPLE = "#4C72B0", "#55A868", "#C44E52", "#DD8452", "#8172B3"


def fig_part1():
    d = df[df.experiment == "part1"]
    order = ["UNIFORM", "MONOTONE", "SKEWED"]
    means = [float(d[d.workload == w].rank_mean.iloc[0]) for w in order]
    fig, ax = plt.subplots(figsize=(5.2, 3.4))
    bars = ax.bar(order, means, color=[BLUE, GREEN, RED])
    ax.axhline(THEORY, ls="--", color="black", lw=1.2,
               label=f"uniform-load theory (5/6)·n = {THEORY:.2f}")
    for b, m in zip(bars, means):
        ax.text(b.get_x() + b.get_width() / 2, m, f"{m:.1f}", ha="center", va="bottom", fontsize=9)
    ax.set_ylabel("mean rank error")
    ax.set_title("Part 1: rank error vs workload (n = c·p = 8, d = 2)")
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig("fig1_part1_weakness.png")


def fig_part2():
    d = df[df.experiment == "part2"]
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.6), sharex=True)
    for ax, wl in zip(axes, ["UNIFORM", "SKEWED"]):
        s = d[d.workload == wl].sort_values("d")
        ax2 = ax.twinx()
        l1, = ax.plot(s.d, s.throughput_ops / 1e6, "o-", color=BLUE, label="throughput")
        l2, = ax2.plot(s.d, s.rank_mean, "s--", color=RED, label="rank error")
        ax2.set_yscale("log")
        ax.set_xlabel("d (queues sampled per deleteMin)")
        ax.set_ylabel("throughput (M ops/s)", color=BLUE)
        ax2.set_ylabel("mean rank error (log)", color=RED)
        ax.set_title(wl)
        ax.set_xticks([1, 2, 4, 8])
    axes[0].legend(handles=[l1, l2], fontsize=8, loc="center right")
    fig.suptitle("Part 2: throughput ↔ quality trade-off across d", y=1.02)
    fig.tight_layout()
    fig.savefig("fig2_part2_dsweep.png", bbox_inches="tight")


def _winner_seq(workload, syncPeriod, ringSize):
    q = df[(df.experiment == "part3q") & (df.strategy == "WINNER") & (df.workload == workload)
           & (df.syncPeriod.astype(str) == str(syncPeriod)) & (df.ringSize == ringSize)]
    return float(q.rank_mean.iloc[0]), float(q.rank_p99.iloc[0])


def _seq(strategy, workload):
    q = df[(df.experiment == "part3q") & (df.strategy == strategy) & (df.workload == workload)
           & (df.d == 2) & (df.ringSize.isin([0, 1, 4]))]
    q = q[q.strategy == strategy]
    return float(q.rank_mean.iloc[0]), float(q.rank_p99.iloc[0])


def fig_part3_quality():
    # main plot: sequential exact rank error on SKEWED. UNIFORM vs the assigned STALE vs WINNER.
    # overlay part3cq (concurrent-interleaved) so you can see the win doesn't evaporate under
    # concurrency. d=3 and d=4 go in as dashed lines to answer "why not just bump d".
    labels = ["UNIFORM\n(d=2)", "STALE\n(d=2)", "WINNER\nM=1", "WINNER\nM=4", "WINNER\nM=4,ε=1/8"]
    uni_m, uni_p = _seq("UNIFORM", "SKEWED")
    sta_m, sta_p = _seq("STALE", "SKEWED")
    w1_m, w1_p = _winner_seq("SKEWED", "never", 1)
    w4_m, w4_p = _winner_seq("SKEWED", "never", 4)
    we_m, we_p = _winner_seq("SKEWED", "8", 4)
    means = [uni_m, sta_m, w1_m, w4_m, we_m]
    p99s = [uni_p, sta_p, w1_p, w4_p, we_p]
    colors = [BLUE, GREEN, PURPLE, PURPLE, PURPLE]

    fig, (axm, axc) = plt.subplots(1, 2, figsize=(9.4, 3.7))

    x = range(len(labels))
    bars = axm.bar(x, means, color=colors)
    for b, m in zip(bars, means):
        axm.text(b.get_x() + b.get_width() / 2, m, f"{m:.1f}", ha="center", va="bottom", fontsize=8.5)
    d3 = float(df[(df.experiment == "part3q") & (df.strategy == "UNIFORM") & (df.workload == "SKEWED") & (df.d == 3)].rank_mean.iloc[0])
    d4 = float(df[(df.experiment == "part3q") & (df.strategy == "UNIFORM") & (df.workload == "SKEWED") & (df.d == 4)].rank_mean.iloc[0])
    axm.axhline(d3, ls=":", color="gray", lw=1.1, label=f"UNIFORM d=3 ({d3:.1f})")
    axm.axhline(d4, ls="--", color="gray", lw=1.1, label=f"UNIFORM d=4 ({d4:.1f})")
    axm.set_xticks(list(x)); axm.set_xticklabels(labels, fontsize=8)
    axm.set_ylabel("mean rank error (sequential, exact)")
    axm.set_title("Part 3a: thread-local sampling quality on SKEWED")
    axm.legend(fontsize=7.5)

    # concurrent-interleaved check
    cq = df[df.experiment == "part3cq"]
    cqo = ["UNIFORM", "STALE", "WINNER"]
    cqm = [float(cq[cq.strategy == s].rank_mean.iloc[0]) for s in cqo]
    cbars = axc.bar(cqo, cqm, color=[BLUE, GREEN, PURPLE])
    for b, m in zip(cbars, cqm):
        axc.text(b.get_x() + b.get_width() / 2, m, f"{m:.1f}", ha="center", va="bottom", fontsize=8.5)
    axc.set_ylabel("mean rank error (concurrent, exact)")
    axc.set_title("Part 3b: the win survives P=8 interleaving")
    fig.tight_layout()
    fig.savefig("fig3_part3_quality.png", bbox_inches="tight")


def fig_part3_tradeoff():
    # left: exploration dial, rank error and throughput vs forced re-explore period.
    # right: per-strategy concurrent throughput + deleteMin lock-fail% (herding check),
    # with the STALE global-sync collapse column for contrast.
    fig, (axd, axt) = plt.subplots(1, 2, figsize=(9.4, 3.7))

    eps = df[df.experiment == "part3eps"].copy()
    order = ["never", "16", "8", "4"]
    eps["k"] = eps.syncPeriod.astype(str)
    eps = eps.set_index("k").loc[order].reset_index()
    xlab = ["greedy", "1/16", "1/8", "1/4"]
    xx = range(len(xlab))
    ax2 = axd.twinx()
    l1, = axd.plot(xx, eps.throughput_ops / 1e6, "o-", color=BLUE, label="throughput")
    l2, = ax2.plot(xx, eps.rank_mean, "s--", color=RED, label="rank error")
    axd.set_xticks(list(xx)); axd.set_xticklabels(xlab)
    axd.set_xlabel("forced re-exploration probability")
    axd.set_ylabel("throughput (M ops/s)", color=BLUE)
    ax2.set_ylabel("mean rank error", color=RED)
    axd.set_title("Part 3c: WINNER exploration dial (SKEWED)")
    axd.legend(handles=[l1, l2], fontsize=8, loc="center right")

    thr = df[df.experiment == "part3thr"]
    def get(strat, ring):
        r = thr[(thr.strategy == strat) & (thr.ringSize == ring)]
        return float(r.throughput_ops.iloc[0]) / 1e6, float(r.lockFailPct.iloc[0])
    names = ["UNIFORM", "STALE", "WINNER\nM=4 ε=1/8"]
    tvals = [get("UNIFORM", 0)[0], get("STALE", 0)[0], get("WINNER", 4)[0]]
    lvals = [get("UNIFORM", 0)[1], get("STALE", 0)[1], get("WINNER", 4)[1]]
    sync1 = df[(df.experiment == "part3sync") & (df.syncPeriod.astype(str) == "1")]
    tvals.append(float(sync1.throughput_ops.iloc[0]) / 1e6)
    lvals.append(float(sync1.lockFailPct.iloc[0]))
    names.append("STALE\nsync/op")
    cols = [BLUE, GREEN, PURPLE, RED]
    x = range(len(names))
    bars = axt.bar(x, tvals, color=cols)
    for b, t, lf in zip(bars, tvals, lvals):
        axt.text(b.get_x() + b.get_width() / 2, t, f"{t:.0f}M\n{lf:.0f}% lf", ha="center", va="bottom", fontsize=7.5)
    axt.set_xticks(list(x)); axt.set_xticklabels(names, fontsize=8)
    axt.set_ylabel("throughput (M ops/s)")
    axt.set_ylim(0, max(tvals) * 1.25)
    axt.set_title("Part 3d: throughput + lock-fail (no herding; sync/op collapses)")
    fig.tight_layout()
    fig.savefig("fig4_part3_tradeoff.png", bbox_inches="tight")


def fig_scale():
    d = df[df.experiment == "scale"]
    fig, ax = plt.subplots(figsize=(5.6, 3.6))
    for strat, color, mark in [("StrictPQ", RED, "s"), ("MultiQueue", BLUE, "o")]:
        s = d[d.strategy == strat].sort_values("threads")
        ax.plot(s.threads, s.throughput_ops / 1e6, mark + "-", color=color, label=strat)
    ax.set_xlabel("threads")
    ax.set_ylabel("throughput (M ops/s)")
    ax.set_xscale("log", base=2)
    ax.set_xticks(sorted(d.threads.unique()))
    ax.get_xaxis().set_major_formatter(matplotlib.ticker.ScalarFormatter())
    ax.set_title("Scalability: StrictPQ (single lock) vs MultiQueue")
    ax.legend(fontsize=9)
    fig.tight_layout()
    fig.savefig("fig5_scalability.png")


fig_part1()
fig_part2()
fig_part3_quality()
fig_part3_tradeoff()
fig_scale()
print("wrote fig1_part1_weakness.png fig2_part2_dsweep.png fig3_part3_quality.png "
      "fig4_part3_tradeoff.png fig5_scalability.png")
