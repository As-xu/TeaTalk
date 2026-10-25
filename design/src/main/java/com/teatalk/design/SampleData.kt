package com.teatalk.design

import androidx.annotation.DrawableRes

data class SampleChar(
    val name: String,
    @DrawableRes val cover: Int,
    val author: String,
    val tags: List<String>,
    val last: String,
    val time: String,
    val unread: Int = 0,
    val intro: String = "",
)

val sampleChars = listOf(
    SampleChar(
        name = "许知与",
        cover = R.drawable.cover_1,
        author = "月下茶",
        tags = listOf("现代", "职场", "慢热"),
        last = "「……你今天，穿得不像来谈合同的。」",
        time = "13:20",
        unread = 2,
        intro = "雨夜的茶室里，她摘下眼镜，合同摊开在桌上没有翻页。\n\n" +
            "许知与，三十二岁，做收购谈判第八年。习惯先听完对方所有的话，再决定哪一句是真的。",
    ),
    SampleChar(
        name = "叶舒宁",
        cover = R.drawable.cover_2,
        author = "Ye",
        tags = listOf("古风", "温柔"),
        last = "叶舒宁：茶要凉了。",
        time = "12:04",
        intro = "山中的茶舍只有两间屋子，一间煮水，一间待客。\n\n" +
            "她记得你上次来是三个月前，也记得你那天没喝完的那杯。",
    ),
    SampleChar(
        name = "The Demon Who Haunts You",
        cover = R.drawable.cover_3,
        author = "pudding",
        tags = listOf("奇幻", "搞笑", "恶魔"),
        last = "*戳了戳你的脸* 喂，别装睡。",
        time = "昨天",
        intro = "她是你床底的恶魔，业务能力为零，话很多。\n\n" +
            "诅咒你失败第一百三十七次之后，她决定改用烦死你的方式。",
    ),
    SampleChar(
        name = "Soulbound Fugitive",
        cover = R.drawable.cover_4,
        author = "Aria",
        tags = listOf("现代", "都市"),
        last = "You: 我在楼下。",
        time = "昨天",
        intro = "你在便利店门口捡到她的时候，她正用一枚硬币决定要不要活下去。\n\n" +
            "现在她住在你家沙发，喝你的茶，管你的闲事。",
    ),
    SampleChar(
        name = "You Died Once…",
        cover = R.drawable.cover_5,
        author = "Obsidian",
        tags = listOf("异世界", "病娇"),
        last = "*她（小皇帝）把奏折推到你面前*",
        time = "周三",
        intro = "上一世你死于她登基那天的雪。\n\n" +
            "这一世，她只有十三岁，还没有学会怎么杀人。",
    ),
    SampleChar(
        name = "DI Instructor",
        cover = R.drawable.cover_6,
        author = "K.",
        tags = listOf("现代", "教官"),
        last = "「再加十组。」",
        time = "10/21",
        intro = "训练场下午三点最热，她从不戴表。\n\n" +
            "「跑完再说。」她说这句话时甚至没抬眼看你。",
    ),
)
