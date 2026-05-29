package com.blackclaw.android.base

import android.os.Bundle
import android.view.View

/**
 * Base Activity class
 */
abstract class AbstractBaseActivity : BaseActivity() {
    protected open var TAG: String? = this::class.java.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(setContentLayout())
        initView(savedInstanceState)
        initData()
        setListener()
        loadData()
    }


    /**
     * Set layout
     */
    open abstract fun setContentLayout(): View

    /**
     * Initialize layout
     * @param savedInstanceState Bundle?
     */
    abstract fun initView(savedInstanceState: Bundle?)

    /**
     * Initialize data
     */
    open fun initData() {

    }

    open fun setListener() {

    }

    open fun loadData() {


    }

}