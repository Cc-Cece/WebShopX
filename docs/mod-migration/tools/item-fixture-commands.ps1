param(
    [Parameter(Mandatory = $true)][string]$Minecraft
)

$ErrorActionPreference = 'Stop'
$parts = $Minecraft.Split('.')
try {
    $major = [int]$parts[0]
    $minor = if ($parts.Count -gt 1) { [int]$parts[1] } else { 0 }
    $patch = if ($parts.Count -gt 2) { [int]($parts[2] -replace '[^0-9].*$', '') } else { 0 }
} catch {
    throw "Unsupported Minecraft version for controlled item fixtures: $Minecraft"
}
$modern = $major -gt 1 -or $minor -gt 20 -or ($minor -eq 20 -and $patch -ge 5)

$commands = if ($modern) {
    @(
        'give {player} minecraft:diamond_sword[minecraft:damage=7,minecraft:enchantments={"minecraft:sharpness":5},minecraft:custom_data={kind:"wx_enchanted_durable"}] 1'
        'give {player} minecraft:potion[minecraft:potion_contents={potion:"minecraft:strong_healing",custom_effects:[{id:"minecraft:speed",amplifier:1,duration:600}]},minecraft:custom_data={kind:"wx_potion"}] 1'
        'give {player} minecraft:written_book[minecraft:written_book_content={title:"Fixture Book",author:"WebShopX",pages:[''{"text":"fixture page"}'']},minecraft:custom_data={kind:"wx_written_book"}] 1'
        'give {player} minecraft:filled_map[minecraft:map_id=7,minecraft:custom_data={kind:"wx_map"}] 1'
        'give {player} minecraft:shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:diamond",count:3,components:{"minecraft:custom_data":{kind:"nested_level_1"}}}},{slot:1,item:{id:"minecraft:bundle",count:1,components:{"minecraft:bundle_contents":[{id:"minecraft:emerald",count:2,components:{"minecraft:custom_data":{kind:"nested_level_2"}}}]}}}],minecraft:custom_data={kind:"wx_shulker_nested"}] 1'
        'give {player} minecraft:bundle[minecraft:bundle_contents=[{id:"minecraft:shulker_box",count:1,components:{"minecraft:container":[{slot:0,item:{id:"minecraft:gold_ingot",count:2,components:{"minecraft:custom_data":{kind:"nested_level_2"}}}}]}}],minecraft:custom_data={kind:"wx_bundle_nested"}] 1'
        'give {player} webshopx_fixture:data_item[minecraft:custom_data={kind:"wx_mod_item",value:42}] 1'
    )
} else {
    @(
        'give {player} minecraft:diamond_sword{Damage:7,Enchantments:[{id:"minecraft:sharpness",lvl:5s}],WebShopXFixture:{kind:"wx_enchanted_durable"}} 1'
        'give {player} minecraft:potion{Potion:"minecraft:strong_healing",CustomPotionEffects:[{Id:1b,Amplifier:1b,Duration:600}],WebShopXFixture:{kind:"wx_potion"}} 1'
        'give {player} minecraft:written_book{title:"Fixture Book",author:"WebShopX",pages:[''{"text":"fixture page"}''],WebShopXFixture:{kind:"wx_written_book"}} 1'
        'give {player} minecraft:filled_map{map:7,WebShopXFixture:{kind:"wx_map"}} 1'
        'give {player} minecraft:shulker_box{BlockEntityTag:{Items:[{Slot:0b,id:"minecraft:diamond",Count:3b,tag:{WebShopXFixture:{kind:"nested_level_1"}}},{Slot:1b,id:"minecraft:bundle",Count:1b,tag:{Items:[{id:"minecraft:emerald",Count:2b,tag:{WebShopXFixture:{kind:"nested_level_2"}}}]}}]},WebShopXFixture:{kind:"wx_shulker_nested"}} 1'
        'give {player} minecraft:bundle{Items:[{id:"minecraft:shulker_box",Count:1b,tag:{BlockEntityTag:{Items:[{Slot:0b,id:"minecraft:gold_ingot",Count:2b,tag:{WebShopXFixture:{kind:"nested_level_2"}}}]}}}],WebShopXFixture:{kind:"wx_bundle_nested"}} 1'
        'give {player} webshopx_fixture:data_item{WebShopXFixture:{kind:"wx_mod_item",value:42}} 1'
    )
}

@($commands) | ConvertTo-Json
