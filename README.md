# QAPI3
## Fast, SQL-safe Api.
This program contains these features:
- Minecraft Server validation: User can easily bind their account to api,
- Chat Sync between several Minecraft Servers(WIP): This feature allows users to chat across servers.
- Minecraft Server Status Query: This api will automatically show latest server status.(Need Plugin-side support)

## Future RoadMaps
More codes in Kotlin

Rankings (Like destroy & place) with QPlugin

DIY player card with QCommunity WEB

Add Discord support
## Guides
### To install:
    
simply just run gradle build and that's all. Don't forget to add configurations like MySQL server and Redis!

I recommend you to just run program in docker and redirect port to nginx, etc.

To add components, sub servers, please add node object in `nodes.json`

```JSON
[
  {
    "name": "QQ",
    "id": 0,
    "role": "SERVER",
    "token": "123456"
  },
  {
    "name": "QO",
    "id": 1,
    "role": "SERVER",
    "token": "123456"
  }
]
```

and then they can post messages, add new features, etc...

### API Endpoint

GET `/qo/download/status` -> 
``` JSON
{
    "totalcount": 0,
    "mspt_3s": 2,
    "code": 0,
    "players": [],
    "game_time": 0,
    "mspt": 2.562659,
    "recent60": [],
    "onlinecount": 0,
    "timestamp": 1727272149198
}
```

GET `/qo/msglist/download` -> 
```JSON
{
    "messages": [
        "服务状态更新：\nService SortMC 的状态为 null\n最新的heartbeat状态为： 1 延迟 77ms",
        "[QO] 玩家Lplayer加入了服务器。",
        "[QO] 玩家Lplayer退出了服务器，本次游玩时间 4分钟",
        "[QO] 玩家Lplayer加入了服务器。",
        "服务状态更新：\nService QAPI origin 的状态为 null\n最新的heartbeat状态为： 0 延迟 0ms",
        "[QQ] <CHJWOS_|2859972822>:wow",
        "[QQ] <CHJWOS_|2859972822>:https://www.mcmod.cn/class/14114.html",
        "[QQ] <glowingstone124|1294915648>:[CQ:reply,id=986310598][CQ:at,qq=2859972822,name=CHJWOS_] fabric mod自定义gui的话",
        "[QQ] <glowingstone124|1294915648>:是不是要拿opengl嗯写",
        "[QQ] <CHJWOS_|2859972822>:高版本可能是有原版帮你写好了的",
        "[QQ] <CHJWOS_|2859972822>:不是原版风格的就自己写",
        "[QQ] <glowingstone124|1294915648>:我不想要原版",
        "[QQ] <glowingstone124|1294915648>:我想写个现代化ui",
        "[QQ] <CHJWOS_|2859972822>:矩形啥的应该是已经内置了",
        "[QQ] <CHJWOS_|2859972822>:如果有模糊啥的",
        "[QQ] <CHJWOS_|2859972822>:可能1.20.5往上也有",
        "[QQ] <glowingstone124|1294915648>:行",
        "[QQ] <glowingstone124|1294915648>:动画呢",
        "[QQ] <glowingstone124|1294915648>:是不是要自己搓",
        "[QQ] <CHJWOS_|2859972822>:是了",
        "[QQ] <東雪蓮Official|3125265713>:累了",
        "[QQ] <東雪蓮Official|3125265713>:今天操场上测了下速",
        "[QQ] <東雪蓮Official|3125265713>:23圈",
        "[QQ] <東雪蓮Official|3125265713>:一圈400",
        "[QQ] <東雪蓮Official|3125265713>:15分钟",
        "[QQ] <東雪蓮Official|3125265713>:每小时大约36km",
        "[QQ] <東雪蓮Official|3125265713>:[CQ:image,file=6EF6BBF5A4EC9B58B3754A1E7836C689.jpg,subType=1,url=https://multimedia.nt.qq.com.cn/download?appid=1407&amp;fileid=CgozMTI1MjY1NzEzEhT-HH8woFCrx7llrTrMAfDy9YbiQBiutiMg_woo9s29x5beiAMyBHByb2RQgL2jAQ&amp;spec=0&amp;rkey=CAMSKMa3OFokB_TlE5oz_MZGn_1PxOOLL_sQeAG7OFPt_2onFxvUsjDhYv0,file_size=580398]",
        "[QQ] <CHJWOS_|2859972822>:哇啊",
        "[QQ] <CHJWOS_|2859972822>:全世界的人都在教我怎么sampler2D传入图片",
        "[QQ] <CHJWOS_|2859972822>:我想要传入当前帧画面的教程啊",
        "[QQ] <glowingstone124|1294915648>:那你把这一帧变成bytemap"
    ],
    "empty": false
}
```

GET `/qo/download/registry?name=glowingstone124` -> 
```JSON
{
    "qq": 1294915648,
    "code": 0,
    "frozen": false,
    "online": false,
    "economy": 0,
    "playtime": 1712
}
```

