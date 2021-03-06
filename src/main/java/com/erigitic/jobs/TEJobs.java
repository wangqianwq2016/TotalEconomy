/*
 * This file is part of Total Economy, licensed under the MIT License (MIT).
 *
 * Copyright (c) Eric Grandt <https://www.ericgrandt.com>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.erigitic.jobs;

import com.erigitic.config.AccountManager;
import com.erigitic.config.TEAccount;
import com.erigitic.jobs.jobs.FishermanJob;
import com.erigitic.jobs.jobs.LumberjackJob;
import com.erigitic.jobs.jobs.MinerJob;
import com.erigitic.jobs.jobs.WarriorJob;
import com.erigitic.main.TotalEconomy;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import org.slf4j.Logger;
import org.spongepowered.api.block.tileentity.Sign;
import org.spongepowered.api.block.tileentity.TileEntity;
import org.spongepowered.api.data.Transaction;
import org.spongepowered.api.data.manipulator.mutable.item.FishData;
import org.spongepowered.api.data.manipulator.mutable.tileentity.SignData;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.action.FishingEvent;
import org.spongepowered.api.event.block.ChangeBlockEvent;
import org.spongepowered.api.event.block.InteractBlockEvent;
import org.spongepowered.api.event.block.tileentity.ChangeSignEvent;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.NamedCause;
import org.spongepowered.api.event.cause.entity.damage.source.EntityDamageSource;
import org.spongepowered.api.event.entity.DestructEntityEvent;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.item.inventory.ItemStackSnapshot;
import org.spongepowered.api.scheduler.Scheduler;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class TEJobs {
    private TotalEconomy totalEconomy;
    private AccountManager accountManager;
    private ConfigurationNode accountConfig;
    private Logger logger;

    private File jobsFile;
    private ConfigurationLoader<CommentedConfigurationNode> loader;
    private ConfigurationNode jobsConfig;

    private MinerJob miner;
    private LumberjackJob lumberjack;
    private WarriorJob warrior;
    private FishermanJob fisherman;

    public TEJobs(TotalEconomy totalEconomy) {
        this.totalEconomy = totalEconomy;

        miner = new MinerJob();
        lumberjack = new LumberjackJob();
        warrior = new WarriorJob();
        fisherman = new FishermanJob();

        accountManager = totalEconomy.getAccountManager();
        accountConfig = accountManager.getAccountConfig();
        logger = totalEconomy.getLogger();

        setupConfig();

        if (totalEconomy.isLoadSalary())
            startSalaryTask();
    }

    /**
     * Start the timer that pays out the salary to each player after a specified time in seconds
     */
    private void startSalaryTask() {
        Scheduler scheduler = totalEconomy.getGame().getScheduler();
        Task.Builder payTask = scheduler.createTaskBuilder();

        Task task = payTask.execute(() -> {
                for (Player player : totalEconomy.getServer().getOnlinePlayers()) {
                    BigDecimal salary = new BigDecimal(jobsConfig.getNode(getPlayerJob(player), "salary").getString());
                    boolean salaryDisabled = jobsConfig.getNode(getPlayerJob(player), "disablesalary").getBoolean();

                    if (!salaryDisabled) {
                        TEAccount playerAccount = (TEAccount) accountManager.getOrCreateAccount(player.getUniqueId()).get();

                        playerAccount.deposit(totalEconomy.getDefaultCurrency(), salary, Cause.of(NamedCause.of("TotalEconomy", totalEconomy.getPluginContainer())));
                        player.sendMessage(Text.of(TextColors.GRAY, "Your salary of ", TextColors.GOLD,
                                totalEconomy.getCurrencySymbol(), salary, TextColors.GRAY, " has just been paid."));
                    }
                }
        }).delay(jobsConfig.getNode("salarydelay").getInt(), TimeUnit.SECONDS).interval(jobsConfig.getNode("salarydelay")
                .getInt(), TimeUnit.SECONDS).name("Pay Day").submit(totalEconomy);
    }

    /**
     * Setup the jobs config
     */
    public void setupConfig() {
        jobsFile = new File(totalEconomy.getConfigDir(), "jobs.conf");
        loader = HoconConfigurationLoader.builder().setFile(jobsFile).build();

        try {
            jobsConfig = loader.load();

            if (!jobsFile.exists()) {
                jobsConfig.getNode("jobs").setValue("Miner, Lumberjack, Warrior, Fisherman");

                miner.setupJobValues(jobsConfig);
                lumberjack.setupJobValues(jobsConfig);
                warrior.setupJobValues(jobsConfig);
                fisherman.setupJobValues(jobsConfig);

                jobsConfig.getNode("Unemployed", "disablesalary").setValue(false);
                jobsConfig.getNode("Unemployed", "salary").setValue(20);
                jobsConfig.getNode("salarydelay").setValue(300);

                loader.save(jobsConfig);
            }
        } catch (IOException e) {
            logger.warn("Could not create jobs config file!");
        }
    }

    /**
     * Reload the jobs config
     */
    public void reloadConfig() {
        try {
            jobsConfig = loader.load();
        } catch (IOException e) {
            logger.warn("Could not reload jobs config file!");
        }
    }

    /**
     * Add exp to player's current job
     *
     * @param player player object
     * @param expAmount amount of exp to be gained
     */
    public void addExp(Player player, int expAmount) {
        String jobName = getPlayerJob(player);
        UUID playerUUID = player.getUniqueId();
        int curExp = accountConfig.getNode(playerUUID.toString(), "jobstats", jobName + "Exp").getInt();

        accountConfig.getNode(playerUUID.toString(), "jobstats", jobName + "Exp").setValue(curExp + expAmount);

        try {
            accountManager.getConfigManager().save(accountConfig);
        } catch (IOException e) {
            logger.warn("Problem saving account config!");
        }

        if (accountConfig.getNode(playerUUID.toString(), "jobnotifications").getBoolean() == true)
            player.sendMessage(Text.of(TextColors.GRAY, "You have gained ", TextColors.GOLD, expAmount, TextColors.GRAY,
                    " exp in the ", TextColors.GOLD, jobName, TextColors.GRAY, " job."));
    }

    /**
     * Checks if the player has enough exp to level up. If they do they will gain a level and their current exp will be
     * reset.
     *
     * @param player player object
     */
    public void checkForLevel(Player player) {
        UUID playerUUID = player.getUniqueId();
        String jobName = getPlayerJob(player);
        int playerLevel = accountConfig.getNode(playerUUID.toString(), "jobstats", jobName + "Level").getInt();
        int playerCurExp = accountConfig.getNode(playerUUID.toString(), "jobstats", jobName + "Exp").getInt();
        int expToLevel = getExpToLevel(player);

        if (playerCurExp >= expToLevel) {
            accountConfig.getNode(playerUUID.toString(), "jobstats", jobName + "Level").setValue(playerLevel + 1);
            accountConfig.getNode(playerUUID.toString(), "jobstats", jobName + "Exp").setValue(playerCurExp - expToLevel);
            player.sendMessage(Text.of(TextColors.GRAY, "Congratulations, you are now a level ", TextColors.GOLD,
                    playerLevel + 1, " ", jobName, "."));
        }
    }

    /**
     * Checks the jobs config for the jobName.
     *
     * @param jobName name of the job
     * @return boolean if the job exists or not
     */
    public boolean jobExists(String jobName) {
        if (jobsConfig.getNode(convertToTitle(jobName)).getValue() != null) {
            return true;
        }

        return false;
    }

    /**
     * Convert strings to titles (title -> Title)
     *
     * @param input the string to be titleized
     * @return String the titileized version of the input
     */
    public String convertToTitle(String input) {
        return input.substring(0, 1).toUpperCase() + input.substring(1).toLowerCase();
    }

    /**
     * Set the player's job.
     *
     * @param player Player object
     * @param jobName name of the job
     */
    public void setJob(Player player, String jobName) {
        UUID playerUUID = player.getUniqueId();
        boolean jobPermissions = totalEconomy.isJobPermissions();

        if (jobExists(jobName)) {
            if ((jobPermissions && player.hasPermission("main.job." + jobName)) || !jobPermissions) {
                jobName = convertToTitle(jobName);

                accountConfig.getNode(playerUUID.toString(), "job").setValue(jobName);

                if (accountConfig.getNode(playerUUID.toString(), "jobstats", jobName + "Level").getValue() == null) {
                    accountConfig.getNode(playerUUID.toString(), "jobstats", jobName + "Level").setValue(1);
                }

                if (accountConfig.getNode(playerUUID.toString(), "jobstats", jobName + "Exp").getValue() == null) {
                    accountConfig.getNode(playerUUID.toString(), "jobstats", jobName + "Exp").setValue(0);
                }

                try {
                    accountManager.getConfigManager().save(accountConfig);
                } catch (IOException e) {
                    logger.warn("Could not save account config while setting job!");
                }

                player.sendMessage(Text.of(TextColors.GRAY, "Your job has been changed to ", TextColors.GOLD, jobName));
            } else {
                player.sendMessage(Text.of(TextColors.RED, "You do not have permission to become this job."));
            }
        } else {
            player.sendMessage(Text.of(TextColors.RED, "[TEJobs] This job does not exist"));
        }
    }

    /**
     * Get the player's current job
     *
     * @param player
     * @return String the job the player currently has
     */
    public String getPlayerJob(Player player) {
        return accountConfig.getNode(player.getUniqueId().toString(), "job").getString();
    }

    /**
     * Get the players exp for the passed in job.
     *
     * @param jobName the name of the job
     * @param player the player object
     * @return int the job exp
     */
    public int getJobExp(String jobName, Player player) {
        return accountConfig.getNode(player.getUniqueId().toString(), "jobstats", jobName + "Exp").getInt();
    }

    /**
     * Get the players level for the passed in job
     *
     * @param jobName the name of the job
     * @param player the player object
     * @return int the job level
     */
    public int getJobLevel(String jobName, Player player) {
        return accountConfig.getNode(player.getUniqueId().toString(), "jobstats", jobName + "Level").getInt();
    }

    /**
     * Get the exp required to level.
     *
     * @param player player object
     * @return int the amount of exp needed to level
     */
    public int getExpToLevel(Player player) {
        UUID playerUUID = player.getUniqueId();
        String jobName = getPlayerJob(player);
        int playerLevel = accountConfig.getNode(playerUUID.toString(), "jobstats", jobName + "Level").getInt();

        return playerLevel * 100;
    }

    /**
     * Gets a list of all of the jobs currently in the jobs config.
     *
     * @return String list of jobs
     */
    public String getJobList() {
        return jobsConfig.getNode("jobs").getString();
    }

    /**
     * Getter for the jobs configuration
     *
     * @return ConfigurationNode the jobs configuration
     */
    public ConfigurationNode getJobsConfig() {
        return jobsConfig;
    }

    /**
     * Checks sign contents and converts it to a "Job Changing" sign if conditions are met
     *
     * @param event ChangeSignEvent
     */
    @Listener
    public void onJobSignCheck(ChangeSignEvent event) {
        SignData data = event.getText();
        Text lineOne = data.lines().get(0);
        Text lineTwo = data.lines().get(1);
        String lineOnePlain = lineOne.toPlain();
        String lineTwoPlain = lineTwo.toPlain();

        if (lineOnePlain.equals("[TEJobs]")) {
            lineOne = lineOne.toBuilder().color(TextColors.GOLD).build();

            String jobName = convertToTitle(lineTwoPlain);

            if (jobExists(lineTwoPlain)) {
                lineTwo = Text.of(jobName).toBuilder().color(TextColors.GRAY).build();
            } else {
                lineTwo = Text.of(jobName).toBuilder().color(TextColors.RED).build();
            }

            data.set(data.lines().set(0, lineOne));
            data.set(data.lines().set(1, lineTwo));
            data.set(data.lines().set(2, Text.of()));
            data.set(data.lines().set(3, Text.of()));
        }
    }

    /**
     * Called when a player clicks a sign. If the clicked sign is a "Job Changing" sign then the player's job will
     * be changed on click.
     *
     * @param event InteractBlockEvent
     */
    @Listener
    public void onSignInteract(InteractBlockEvent event) {
        if (event.getCause().first(Player.class).isPresent()) {
            Player player = event.getCause().first(Player.class).get();

            if (event.getTargetBlock().getLocation().isPresent()) {
                Optional<TileEntity> tileEntityOpt = event.getTargetBlock().getLocation().get().getTileEntity();

                if (tileEntityOpt.isPresent()) {
                    TileEntity tileEntity = tileEntityOpt.get();

                    if (tileEntity instanceof Sign) {
                        Sign sign = (Sign) tileEntity;
                        Optional<SignData> data = sign.getOrCreate(SignData.class);

                        if (data.isPresent()) {
                            SignData signData = data.get();
                            Text lineOneText = signData.lines().get(0);
                            Text lineTwoText = signData.lines().get(1);
                            String lineOne = lineOneText.toPlain();
                            String lineTwo = lineTwoText.toPlain();

                            if (lineOne.equals("[TEJobs]") && jobExists(lineTwo)) {
                                setJob(player, lineTwo);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Used for the break option in jobs. Will check if the job has the break node and if it does it will check if the
     * block that was broken is present in the config of the player's job. If it is, it will grab the job exp reward as
     * well as the pay.
     *
     * @param event ChangeBlockEvent.Break
     */
    @Listener
    public void onPlayerBlockBreak(ChangeBlockEvent.Break event) {
        if (event.getCause().first(Player.class).isPresent()) {
            Player player = event.getCause().first(Player.class).get();
            UUID playerUUID = player.getUniqueId();
            String playerJob = getPlayerJob(player);

            if (event.getTransactions().get(0).getOriginal().getState().getType().getName().split(":").length >= 2) {
                String blockName = event.getTransactions().get(0).getOriginal().getState().getType().getName().split(":")[1];
                Optional<UUID> blockCreator = event.getTransactions().get(0).getOriginal().getCreator();

                // Checks if the users current job has the break node.
                boolean hasBreakNode = (jobsConfig.getNode(playerJob, "break").getValue() != null);

                if (jobsConfig.getNode(playerJob).getValue() != null) {
                    if (hasBreakNode && jobsConfig.getNode(playerJob, "break", blockName).getValue() != null) {
                        if (!blockCreator.isPresent()) {
                            int expAmount = jobsConfig.getNode(playerJob, "break", blockName, "expreward").getInt();
                            boolean notify = accountConfig.getNode(playerUUID.toString(), "jobnotifications").getBoolean();

                            BigDecimal payAmount = new BigDecimal(jobsConfig.getNode(playerJob, "break", blockName, "pay").getString()).setScale(2, BigDecimal.ROUND_DOWN);

                            TEAccount playerAccount = (TEAccount) accountManager.getOrCreateAccount(player.getUniqueId()).get();

                            if (notify) {
                                player.sendMessage(Text.of(TextColors.GOLD, accountManager.getDefaultCurrency().getSymbol(), payAmount, TextColors.GRAY, " has been added to your balance."));
                            }

                            addExp(player, expAmount);
                            playerAccount.deposit(accountManager.getDefaultCurrency(), payAmount, Cause.of(NamedCause.of("TotalEconomy", totalEconomy.getPluginContainer())));
                            checkForLevel(player);
                        }
                    }
                }
            }
        }
    }

    /**
     * Used for the place option in jobs. Will check if the job has the place node and if it does it will check if the
     * block that was placed is present in the config of the player's job. If it is, it will grab the job exp reward as
     * well as the pay.
     *
     * @param event ChangeBlockEvent.Place
     */
    @Listener
    public void onPlayerPlaceBlock(ChangeBlockEvent.Place event) {
        if (event.getCause().first(Player.class).isPresent()) {
            Player player = event.getCause().first(Player.class).get();
            UUID playerUUID = player.getUniqueId();
            String playerJob = getPlayerJob(player);
            String blockName = event.getTransactions().get(0).getFinal().getState().getType().getName().split(":")[1];

            //Checks if the users current job has the place node.
            boolean hasPlaceNode = (jobsConfig.getNode(playerJob, "place").getValue() != null);

            if (jobsConfig.getNode(playerJob).getValue() != null) {
                if (hasPlaceNode && jobsConfig.getNode(playerJob, "place", blockName).getValue() != null) {
                    int expAmount = jobsConfig.getNode(playerJob, "place", blockName, "expreward").getInt();
                    boolean notify = accountConfig.getNode(playerUUID.toString(), "jobnotifications").getBoolean();

                    BigDecimal payAmount = new BigDecimal(jobsConfig.getNode(playerJob, "place", blockName, "pay").getString()).setScale(2, BigDecimal.ROUND_DOWN);

                    TEAccount playerAccount = (TEAccount) accountManager.getOrCreateAccount(player.getUniqueId()).get();

                    if (notify) {
                        player.sendMessage(Text.of(TextColors.GOLD, accountManager.getDefaultCurrency().getSymbol(), payAmount, TextColors.GRAY, " has been added to your balance."));
                    }

                    addExp(player, expAmount);
                    playerAccount.deposit(accountManager.getDefaultCurrency(), payAmount, Cause.of(NamedCause.of("TotalEconomy", totalEconomy.getPluginContainer())));
                    checkForLevel(player);
                }
            }
        }
    }

    /**
     * Used for the break option in jobs. Will check if the job has the break node and if it does it will check if the
     * block that was broken is present in the config of the player's job. If it is, it will grab the job exp reward as
     * well as the pay.
     *
     * @param event DesctructEntityEvent.Death
     */
    @Listener
    public void onPlayerKillEntity(DestructEntityEvent.Death event) {
        Optional<EntityDamageSource> optDamageSource = event.getCause().first(EntityDamageSource.class);

        if (optDamageSource.isPresent()) {
            EntityDamageSource damageSource = optDamageSource.get();
            Entity killer = damageSource.getSource();
            Entity victim = event.getTargetEntity();

            if (killer instanceof Player) {
                Player player = (Player) killer;
                UUID playerUUID = player.getUniqueId();
                String playerJob = getPlayerJob(player);
                String victimName = victim.getType().getName();

                boolean hasKillNode = (jobsConfig.getNode(playerJob, "kill").getValue() != null);

                if (jobsConfig.getNode(playerJob).getValue() != null) {
                    if (hasKillNode && jobsConfig.getNode(playerJob, "kill", victimName).getValue() != null) {
                        int expAmount = jobsConfig.getNode(playerJob, "kill", victimName, "expreward").getInt();
                        boolean notify = accountConfig.getNode(playerUUID.toString(), "jobnotifications").getBoolean();

                        BigDecimal payAmount = new BigDecimal(jobsConfig.getNode(playerJob, "kill", victimName, "pay").getString()).setScale(2, BigDecimal.ROUND_DOWN);

                        TEAccount playerAccount = (TEAccount) accountManager.getOrCreateAccount(player.getUniqueId()).get();

                        if (notify) {
                            player.sendMessage(Text.of(TextColors.GOLD, accountManager.getDefaultCurrency().getSymbol(), payAmount, TextColors.GRAY, " has been added to your balance."));
                        }

                        addExp(player, expAmount);
                        playerAccount.deposit(accountManager.getDefaultCurrency(), payAmount, Cause.of(NamedCause.of("TotalEconomy", totalEconomy.getPluginContainer())));
                        checkForLevel(player);
                    }
                }
            }
        }
    }

    /**
     * Used for the catch option in jobs. Will check if the job has the catch node and if it does it will check if the
     * item that was caught is present in the config of the player's job. If it is, it will grab the job exp reward as
     * well as the pay.
     *
     * @param event FishingEvent.Stop
     */
    @Listener
    public void onPlayerFish(FishingEvent.Stop event) {
        if (event.getCause().first(Player.class).isPresent()) {
            Transaction<ItemStackSnapshot> itemTransaction = event.getItemStackTransaction().get(0);
            ItemStack itemStack = itemTransaction.getFinal().createStack();
            Player player = event.getCause().first(Player.class).get();
            UUID playerUUID = player.getUniqueId();
            String playerJob = getPlayerJob(player);

            boolean hasCatchNode = (jobsConfig.getNode(playerJob, "catch").getValue() != null);

            if (itemStack.get(FishData.class).isPresent()) {
                if (jobsConfig.getNode(playerJob).getValue() != null) {
                    FishData fishData = itemStack.get(FishData.class).get();
                    String fishName = fishData.type().get().getName();

                    if (hasCatchNode && jobsConfig.getNode(playerJob, "catch", fishName).getValue() != null) {
                        int expAmount = jobsConfig.getNode(playerJob, "catch", fishName, "expreward").getInt();
                        boolean notify = accountConfig.getNode(playerUUID.toString(), "jobnotifications").getBoolean();

                        BigDecimal payAmount = new BigDecimal(jobsConfig.getNode(playerJob, "catch", fishName, "pay").getString()).setScale(2, BigDecimal.ROUND_DOWN);

                        TEAccount playerAccount = (TEAccount) accountManager.getOrCreateAccount(player.getUniqueId()).get();

                        if (notify) {
                            player.sendMessage(Text.of(TextColors.GOLD, accountManager.getDefaultCurrency().getSymbol(), payAmount, TextColors.GRAY, " has been added to your balance."));
                        }

                        addExp(player, expAmount);
                        playerAccount.deposit(accountManager.getDefaultCurrency(), payAmount, Cause.of(NamedCause.of("TotalEconomy", totalEconomy.getPluginContainer())));
                        checkForLevel(player);
                    }
                }

            }
        }
    }
}