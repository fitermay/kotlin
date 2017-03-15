fun some1() {
    {
        println("BROWSER: ${")
//                val name = "sauce" + toCamelCase(it.browser).capitalize() + "Test"
                project.tasks.create(it.name, SeleniumTest::class.java).apply {
                extensions.extraProperties.set("systemProperties", mapOf(
                Pair("spring.profiles.active", "sauce"),
                Pair("webdriver.sauce.browser", it.browser),
                Pair("webdriver.sauce.version", it.version)
        ))
    }
}
}
}