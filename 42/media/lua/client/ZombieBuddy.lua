local hasShownNotification = false

if ZombieBuddy and ZombieBuddy.LogOverlay then
    ZombieBuddy.LogOverlay.addFilter("[CleanUI] Skipping invalid")
    ZombieBuddy.LogOverlay.addFilter("\" overrides ")
    ZombieBuddy.LogOverlay.addFilter("action was null")
    ZombieBuddy.LogOverlay.addFilter("ATA2Tuning")
    ZombieBuddy.LogOverlay.addFilter("can't find map objects file")
    ZombieBuddy.LogOverlay.addFilter("can't find mod.info in mod dir")
    ZombieBuddy.LogOverlay.addFilter("feedingTrough")
    ZombieBuddy.LogOverlay.addFilter("fmod: FMOD")
    ZombieBuddy.LogOverlay.addFilter("ignoring invalid ItemPicker")
    ZombieBuddy.LogOverlay.addFilter("IngameState.")
    ZombieBuddy.LogOverlay.addFilter("ISExpBar")
    ZombieBuddy.LogOverlay.addFilter("MainScreenState.")
    ZombieBuddy.LogOverlay.addFilter("no room or building")
    ZombieBuddy.LogOverlay.addFilter("no such mesh")
    ZombieBuddy.LogOverlay.addFilter("no such sound")
    ZombieBuddy.LogOverlay.addFilter("no such texture")
    ZombieBuddy.LogOverlay.addFilter("not found in ZONE_MAP")
    ZombieBuddy.LogOverlay.addFilter("OreVein")
    ZombieBuddy.LogOverlay.addFilter("recursive require")
    ZombieBuddy.LogOverlay.addFilter("require(\"")
    ZombieBuddy.LogOverlay.addFilter("setGameSpeed")
    ZombieBuddy.LogOverlay.addFilter("skinningData is null")
    ZombieBuddy.LogOverlay.addFilter("TraitZ ")
end

local function checkZombieBuddyInstallation()
    -- Only check once
    if hasShownNotification then
        return
    end
    
    if ZombieBuddy and ZombieBuddy.getVersion then
        print("[ZombieBuddy] ZombieBuddy.getVersion() = " .. ZombieBuddy.getVersion())
        -- ZombieBuddy is properly installed
        return
    end
    if not ZombieBuddy then
        print("[ZombieBuddy] ZombieBuddy global is nil.")
    else
        print("[ZombieBuddy] ZombieBuddy.getVersion is nil.")
    end
    print("[ZombieBuddy] showing installation notification.")
    
    -- ZombieBuddy is not installed - show notification
    hasShownNotification = true
    
    local function showInstallationDialog()
        local core = getCore()
        if not core then
            return
        end
        
        -- Jumpstart fork: this folder is installed by Jumpstart and holds no jar. The Java part is
        -- Jumpstart.jar in the game folder, started with -javaagent:Jumpstart.jar=hotswap=true, so
        -- there is nothing to copy from here: Jumpstart's installer puts it back.
        local message = getText("UI_ZB_Install_Jumpstart", "https://github.com/SmokerDoker/jumpstart-pz")
        
        -- Show modal dialog like the one in media/lua/client/OptionScreens/MainScreen.lua
        local windowWidth = 600 + (core:getOptionFontSizeReal() * 100)
        local windowHeight = 600
        local screenWidth = core:getScreenWidth()
        local screenHeight = core:getScreenHeight()
        local x = (screenWidth - windowWidth) / 2
        local y = screenHeight / 2 - 300
        
        local modal = ISModalRichText:new(x, y, windowWidth, windowHeight, message, false, nil, nil)
        modal:initialise()
        modal.backgroundColor = {r=0, g=0, b=0, a=0.9}
        modal.alwaysOnTop = true
        modal.chatText:paginate()
        modal:setY(screenHeight / 2 - (modal:getHeight() / 2))
        modal:setVisible(true)
        modal:addToUIManager()
    end
    
    showInstallationDialog()
end

-- Run the check when on main menu
Events.OnMainMenuEnter.Add(checkZombieBuddyInstallation)

